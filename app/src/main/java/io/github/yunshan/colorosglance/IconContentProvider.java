package io.github.yunshan.colorosglance;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

public final class IconContentProvider extends ContentProvider {
    static final String AUTHORITY = "io.github.yunshan.colorosglance.icons";
    static final String APP_PATH = "app";

    private static final String TAG = "ColorOSGlanceExtender";
    private static final String MIME_TYPE = "image/png";
    private static final int ICON_SIZE_PX = 256;
    private static final int CACHE_SIZE_BYTES = 4 * 1024 * 1024;

    private final LruCache<String, byte[]> iconCache =
            new LruCache<String, byte[]>(CACHE_SIZE_BYTES) {
                @Override
                protected int sizeOf(String key, byte[] value) {
                    return value.length;
                }
            };

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return MIME_TYPE;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("read-only provider");
        }
        String packageName = parsePackageName(uri);
        byte[] iconBytes = iconCache.get(packageName);
        if (iconBytes == null) {
            iconBytes = renderApplicationIcon(packageName);
            iconCache.put(packageName, iconBytes);
            Log.i(TAG, "应用图标已生成：package=" + packageName
                    + ", bytes=" + iconBytes.length);
        }
        return openPipeHelper(
                uri,
                MIME_TYPE,
                null,
                iconBytes,
                (output, ignoredUri, ignoredMimeType, ignoredOptions, data) -> {
                    try (OutputStream stream =
                                 new ParcelFileDescriptor.AutoCloseOutputStream(output)) {
                        stream.write(data);
                    } catch (IOException exception) {
                        Log.w(TAG, "应用图标输出失败：package=" + packageName, exception);
                    }
                });
    }

    private static String parsePackageName(Uri uri) throws FileNotFoundException {
        if (!AUTHORITY.equals(uri.getAuthority())) {
            throw new FileNotFoundException("unknown authority");
        }
        List<String> segments = uri.getPathSegments();
        if (segments.size() != 2 || !APP_PATH.equals(segments.get(0))) {
            throw new FileNotFoundException("unknown path");
        }
        String packageName = segments.get(1);
        if (!packageName.matches("[A-Za-z0-9_.]+")) {
            throw new FileNotFoundException("invalid package");
        }
        return packageName;
    }

    private byte[] renderApplicationIcon(String packageName) throws FileNotFoundException {
        if (getContext() == null) {
            throw new FileNotFoundException("provider context unavailable");
        }
        try {
            Drawable icon = getContext().getPackageManager().getApplicationIcon(packageName);
            Bitmap bitmap = Bitmap.createBitmap(
                    ICON_SIZE_PX,
                    ICON_SIZE_PX,
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            icon.setBounds(0, 0, ICON_SIZE_PX, ICON_SIZE_PX);
            icon.draw(canvas);
            ByteArrayOutputStream output = new ByteArrayOutputStream(32 * 1024);
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                bitmap.recycle();
                throw new FileNotFoundException("icon compression failed");
            }
            bitmap.recycle();
            return output.toByteArray();
        } catch (FileNotFoundException exception) {
            throw exception;
        } catch (Throwable throwable) {
            FileNotFoundException exception =
                    new FileNotFoundException("application icon unavailable: " + packageName);
            exception.initCause(throwable);
            throw exception;
        }
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only provider");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only provider");
    }

    @Override
    public int update(
            Uri uri,
            ContentValues values,
            String selection,
            String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only provider");
    }
}
