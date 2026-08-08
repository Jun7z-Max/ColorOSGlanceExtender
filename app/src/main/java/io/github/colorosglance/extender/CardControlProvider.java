package io.github.colorosglance.extender;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class CardControlProvider extends ContentProvider {
    private static final String PREFERENCES = "card_control";
    private static final String DISABLED_IDS = "disabled_ids";
    private static final String CATALOG = "catalog";
    private static final String CATALOG_UPDATED_AT = "catalog_updated_at";
    private static final int MAX_DISABLED_IDS = 4096;
    private static final int MAX_CATALOG_BYTES = 2 * 1024 * 1024;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context context = getContext();
        if (context == null || method == null) {
            return new Bundle();
        }
        switch (method) {
            case ModuleBridge.METHOD_GET_DISABLED:
                return disabledResult(readDisabled(context));
            case ModuleBridge.METHOD_SET_DISABLED:
                ArrayList<String> incoming = extras == null
                        ? null
                        : extras.getStringArrayList(ModuleBridge.KEY_DISABLED_IDS);
                writeDisabled(context, incoming == null
                        ? Collections.emptySet()
                        : new HashSet<>(incoming));
                return disabledResult(readDisabled(context));
            case ModuleBridge.METHOD_GET_CATALOG:
                return catalogResult(context);
            case ModuleBridge.METHOD_PUBLISH_CATALOG:
                saveCatalog(context, extras == null
                        ? null
                        : extras.getString(ModuleBridge.KEY_CATALOG));
                return catalogResult(context);
            case ModuleBridge.METHOD_REQUEST_REFRESH:
                return requestRefreshResult(context);
            default:
                return new Bundle();
        }
    }

    static Set<String> readDisabled(Context context) {
        if (context == null) {
            return Collections.emptySet();
        }
        String encoded = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(DISABLED_IDS, "[]");
        try {
            JSONArray values = new JSONArray(encoded);
            HashSet<String> result = new HashSet<>();
            for (int index = 0; index < values.length() && result.size() < MAX_DISABLED_IDS; index++) {
                String value = values.optString(index, "").trim();
                if (!value.isEmpty() && value.length() <= 512) {
                    result.add(value);
                }
            }
            return result;
        } catch (Throwable ignored) {
            return Collections.emptySet();
        }
    }

    static void writeDisabled(Context context, Set<String> disabledIds) {
        if (context == null) {
            return;
        }
        JSONArray values = new JSONArray();
        if (disabledIds != null) {
            ArrayList<String> sorted = new ArrayList<>(disabledIds);
            Collections.sort(sorted);
            for (String value : sorted) {
                if (value == null || value.length() == 0 || value.length() > 512) {
                    continue;
                }
                values.put(value);
                if (values.length() >= MAX_DISABLED_IDS) {
                    break;
                }
            }
        }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putString(DISABLED_IDS, values.toString())
                .apply();
    }

    static String readCatalog(Context context) {
        if (context == null) {
            return "";
        }
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(CATALOG, "");
    }

    static long readCatalogUpdatedAt(Context context) {
        if (context == null) {
            return 0L;
        }
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getLong(CATALOG_UPDATED_AT, 0L);
    }

    static boolean requestRefresh(Context context) {
        if (context == null) {
            return false;
        }
        try {
            Intent intent = new Intent(ModuleBridge.ACTION_REFRESH);
            intent.setPackage("com.oplus.pantanal.ums");
            intent.putExtra(ModuleBridge.KEY_UPDATED_AT, SystemClock.elapsedRealtime());
            context.sendBroadcast(intent);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void saveCatalog(Context context, String catalog) {
        if (context == null || catalog == null || catalog.length() == 0) {
            return;
        }
        if (catalog.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > MAX_CATALOG_BYTES) {
            return;
        }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putString(CATALOG, catalog)
                .putLong(CATALOG_UPDATED_AT, System.currentTimeMillis())
                .apply();
    }

    private static Bundle disabledResult(Set<String> disabledIds) {
        Bundle result = new Bundle();
        result.putStringArrayList(
                ModuleBridge.KEY_DISABLED_IDS,
                new ArrayList<>(disabledIds == null
                        ? Collections.emptySet()
                        : disabledIds));
        return result;
    }

    private static Bundle catalogResult(Context context) {
        Bundle result = new Bundle();
        result.putString(ModuleBridge.KEY_CATALOG, readCatalog(context));
        result.putLong(ModuleBridge.KEY_UPDATED_AT, readCatalogUpdatedAt(context));
        return result;
    }

    private static Bundle requestRefreshResult(Context context) {
        boolean sent = requestRefresh(context);
        Bundle result = new Bundle();
        result.putBoolean(ModuleBridge.KEY_REFRESH_SENT, sent);
        return result;
    }

    @Override
    public String getType(Uri uri) {
        return "application/json";
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
