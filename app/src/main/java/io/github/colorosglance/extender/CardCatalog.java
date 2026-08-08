package io.github.colorosglance.extender;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Process;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import android.content.res.Resources;

final class CardCatalog {
    private static final String FALLBACK_GROUP = "__third_party_cards__";
    private static final Map<String, Drawable> REMOTE_ICON_CACHE =
            Collections.synchronizedMap(new HashMap<>());

    private CardCatalog() {
    }

    static List<AppGroup> load(Context context) {
        if (context == null) {
            return Collections.emptyList();
        }
        Set<String> disabled = CardControlProvider.readDisabled(context);
        PackageManager packageManager = context.getPackageManager();
        LinkedHashMap<String, Card> cards = new LinkedHashMap<>();
        Map<String, Drawable> iconCache = new HashMap<>();
        String encodedCatalog = CardControlProvider.readCatalog(context);
        parsePublishedCatalog(packageManager, encodedCatalog, cards, disabled, iconCache);
        addInstalledWidgets(context, packageManager, cards, disabled, iconCache);
        return groupCards(packageManager, cards.values());
    }

    private static void parsePublishedCatalog(
            PackageManager packageManager,
            String encodedCatalog,
            Map<String, Card> cards,
            Set<String> disabled,
            Map<String, Drawable> iconCache) {
        if (TextUtils.isEmpty(encodedCatalog)) {
            return;
        }
        try {
            JSONObject root = new JSONObject(encodedCatalog);
            JSONArray values = root.optJSONArray("cards");
            if (values == null) {
                return;
            }
            for (int index = 0; index < values.length(); index++) {
                JSONObject value = values.optJSONObject(index);
                if (value == null) {
                    continue;
                }
                String id = value.optString("id", "").trim();
                if (id.isEmpty()) {
                    continue;
                }
                boolean synthetic = value.optBoolean(
                        "synthetic",
                        !value.optBoolean("official", false));
                if (!synthetic) {
                    continue;
                }
                String packageName = value.optString("packageName", "").trim();
                String componentName = value.optString("componentName", "").trim();
                String appLabel = value.optString("appLabel", "").trim();
                if (appLabel.isEmpty()) {
                    appLabel = resolveApplicationLabel(packageManager, packageName);
                }
                if (appLabel.isEmpty()) {
                    appLabel = packageName.isEmpty() ? "第三方应用" : packageName;
                }
                String title = value.optString("title", "").trim();
                if (title.isEmpty()) {
                    title = shortComponentName(componentName, "卡片");
                }
                String subtitle = value.optString("subtitle", "").trim();
                if (subtitle.isEmpty()) {
                    subtitle = componentName.isEmpty()
                            ? "第三方卡片"
                            : shortComponentName(componentName, componentName);
                }
                String iconSource = value.optString("icon", "").trim();
                Card card = new Card(
                        id,
                        packageName,
                        componentName,
                        appLabel,
                        title,
                        subtitle,
                        !disabled.contains(id),
                        loadCardIcon(packageManager, packageName, iconSource, iconCache));
                addCard(cards, card);
            }
        } catch (Throwable ignored) {
            // 旧版本或损坏缓存不应阻止本地 Widget 列表显示。
        }
    }

    private static void addInstalledWidgets(
            Context context,
            PackageManager packageManager,
            Map<String, Card> cards,
            Set<String> disabled,
            Map<String, Drawable> iconCache) {
        try {
            List<AppWidgetProviderInfo> providers = AppWidgetManager
                    .getInstance(context)
                    .getInstalledProviders();
            for (AppWidgetProviderInfo info : providers) {
                if (!isUsableProvider(info)) {
                    continue;
                }
                ComponentName component = info.provider;
                String packageName = component.getPackageName();
                String componentName = component.getClassName();
                String id = packageName + '/' + componentName;
                if (cards.containsKey(id)) {
                    continue;
                }
                String appLabel = resolveApplicationLabel(packageManager, packageName);
                String title = resolveWidgetLabel(packageManager, info);
                if (title.isEmpty()) {
                    title = shortComponentName(componentName, "卡片");
                }
                Card card = new Card(
                        id,
                        packageName,
                        componentName,
                        appLabel.isEmpty() ? packageName : appLabel,
                        title,
                        shortComponentName(componentName, "桌面小组件"),
                        !disabled.contains(id),
                        loadCardIcon(packageManager, packageName, "", iconCache));
                cards.put(id, card);
            }
        } catch (Throwable ignored) {
            // AppWidgetManager is a best-effort fallback when UMS has not published yet.
        }
    }

    private static List<AppGroup> groupCards(
            PackageManager packageManager,
            Iterable<Card> values) {
        LinkedHashMap<String, AppGroup> groups = new LinkedHashMap<>();
        for (Card card : values) {
            String groupKey = card.packageName.isEmpty() ? FALLBACK_GROUP : card.packageName;
            AppGroup group = groups.get(groupKey);
            if (group == null) {
                String label = card.appLabel.isEmpty() ? "第三方应用" : card.appLabel;
                group = new AppGroup(
                        groupKey,
                        label,
                        card.icon != null
                                ? card.icon
                                : loadApplicationIcon(packageManager, card.packageName));
                groups.put(groupKey, group);
            }
            group.cards.add(card);
        }
        ArrayList<AppGroup> result = new ArrayList<>(groups.values());
        Comparator<AppGroup> groupComparator = Comparator
                .comparing(group -> group.label.toLowerCase(Locale.ROOT));
        result.sort(groupComparator);
        for (AppGroup group : result) {
            group.cards.sort(Comparator.comparing(
                    card -> card.title.toLowerCase(Locale.ROOT)));
        }
        return result;
    }

    private static void addCard(Map<String, Card> cards, Card card) {
        if (!cards.containsKey(card.id)) {
            cards.put(card.id, card);
        }
    }

    private static boolean isUsableProvider(AppWidgetProviderInfo info) {
        if (info == null || info.provider == null) {
            return false;
        }
        try {
            if (info.getProfile() != null
                    && !Process.myUserHandle().equals(info.getProfile())) {
                return false;
            }
            return info.widgetCategory == 0
                    || (info.widgetCategory
                    & AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) != 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String resolveApplicationLabel(
            PackageManager packageManager,
            String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return "";
        }
        try {
            ApplicationInfo info = packageManager.getApplicationInfo(packageName, 0);
            CharSequence label = packageManager.getApplicationLabel(info);
            return label == null ? "" : label.toString().trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String resolveWidgetLabel(
            PackageManager packageManager,
            AppWidgetProviderInfo info) {
        try {
            CharSequence label = info.loadLabel(packageManager);
            return label == null ? "" : label.toString().trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static Drawable loadApplicationIcon(
            PackageManager packageManager,
            String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return null;
        }
        try {
            return packageManager.getApplicationIcon(packageName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Drawable loadCardIcon(
            PackageManager packageManager,
            String packageName,
            String iconSource,
            Map<String, Drawable> iconCache) {
        String source = iconSource == null ? "" : iconSource.trim();
        String cacheKey = packageName + "|" + source;
        if (iconCache != null && iconCache.containsKey(cacheKey)) {
            return iconCache.get(cacheKey);
        }
        Drawable icon = null;
        if (isRemoteIcon(source)) {
            icon = REMOTE_ICON_CACHE.get(source);
            if (icon == null) {
                icon = loadRemoteIcon(source);
                if (icon != null) {
                    REMOTE_ICON_CACHE.put(source, icon);
                }
            }
        }
        if (icon == null) {
            icon = loadApplicationIcon(packageManager, packageName);
        }
        if (iconCache != null) {
            iconCache.put(cacheKey, icon);
        }
        return icon;
    }

    private static boolean isRemoteIcon(String source) {
        return source.startsWith("https://") || source.startsWith("http://");
    }

    private static Drawable loadRemoteIcon(String source) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(source).openConnection();
            connection.setConnectTimeout(1800);
            connection.setReadTimeout(1800);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "ColorOSGlanceExtender/0.1");
            if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) {
                return null;
            }
            int contentLength = connection.getContentLength();
            if (contentLength > 2 * 1024 * 1024) {
                return null;
            }
            try (InputStream stream = connection.getInputStream()) {
                Bitmap bitmap = BitmapFactory.decodeStream(stream);
                return bitmap == null
                        ? null
                        : new BitmapDrawable(Resources.getSystem(), bitmap);
            }
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String shortComponentName(String componentName, String fallback) {
        if (componentName == null || componentName.isEmpty()) {
            return fallback;
        }
        int separator = componentName.lastIndexOf('.');
        String shortName = separator >= 0 && separator + 1 < componentName.length()
                ? componentName.substring(separator + 1)
                : componentName;
        return shortName.startsWith("Widget")
                ? shortName.substring("Widget".length())
                : shortName;
    }

    static final class Card {
        final String id;
        final String packageName;
        final String componentName;
        final String appLabel;
        final String title;
        final String subtitle;
        final Drawable icon;
        boolean enabled;

        Card(
                String id,
                String packageName,
                String componentName,
                String appLabel,
                String title,
                String subtitle,
                boolean enabled,
                Drawable icon) {
            this.id = id;
            this.packageName = packageName == null ? "" : packageName;
            this.componentName = componentName == null ? "" : componentName;
            this.appLabel = appLabel == null ? "" : appLabel;
            this.title = title == null || title.isEmpty() ? "未命名卡片" : title;
            this.subtitle = subtitle == null ? "" : subtitle;
            this.enabled = enabled;
            this.icon = icon;
        }
    }

    static final class AppGroup {
        final String key;
        final String label;
        final Drawable icon;
        final ArrayList<Card> cards = new ArrayList<>();

        AppGroup(String key, String label, Drawable icon) {
            this.key = key;
            this.label = label;
            this.icon = icon;
        }
    }
}
