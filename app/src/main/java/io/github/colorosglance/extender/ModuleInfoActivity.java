package io.github.colorosglance.extender;

import android.app.Activity;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ModuleInfoActivity extends Activity {
    private static final String UI_PREFERENCES = "ui_preferences";
    private static final String DARK_MODE_KEY = "dark_mode";
    private static final int LIGHT_BACKGROUND = Color.rgb(246, 248, 250);
    private static final int LIGHT_SURFACE = Color.WHITE;
    private static final int LIGHT_PRIMARY = Color.rgb(35, 48, 62);
    private static final int LIGHT_SECONDARY = Color.rgb(113, 125, 139);
    private static final int LIGHT_ICON_SURFACE = Color.rgb(239, 243, 246);
    private static final int LIGHT_TOGGLE_SURFACE = Color.rgb(228, 240, 236);
    private static final int LIGHT_ACCENT = Color.rgb(66, 107, 90);
    private static final int DARK_BACKGROUND = Color.rgb(14, 20, 24);
    private static final int DARK_SURFACE = Color.rgb(24, 33, 39);
    private static final int DARK_PRIMARY = Color.rgb(241, 247, 247);
    private static final int DARK_SECONDARY = Color.rgb(165, 181, 186);
    private static final int DARK_ICON_SURFACE = Color.rgb(37, 49, 56);
    private static final int DARK_TOGGLE_SURFACE = Color.rgb(31, 66, 67);
    private static final int DARK_ACCENT = Color.rgb(109, 226, 191);

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService catalogExecutor = Executors.newSingleThreadExecutor();
    private final Map<String, Boolean> expandedGroups = new HashMap<>();
    private final Set<String> disabledCardIds = new HashSet<>();

    private LinearLayout cardList;
    private ScrollView scrollView;
    private ImageButton themeButton;
    private List<CardCatalog.AppGroup> catalog = java.util.Collections.emptyList();
    private boolean loading;
    private boolean darkMode;
    private boolean themeTransitionRunning;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        darkMode = getSharedPreferences(UI_PREFERENCES, MODE_PRIVATE)
                .getBoolean(DARK_MODE_KEY, false);
        configureWindow();
        setContentView(createContentView());
    }

    @Override
    protected void onResume() {
        super.onResume();
        CardControlProvider.requestRefresh(this);
        loadCatalog();
        mainHandler.postDelayed(this::loadCatalog, 900L);
        mainHandler.postDelayed(this::loadCatalog, 1800L);
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        catalogExecutor.shutdownNow();
        super.onDestroy();
    }

    private void configureWindow() {
        Window window = getWindow();
        int background = backgroundColor();
        window.setStatusBarColor(background);
        window.setNavigationBarColor(background);
        window.getDecorView().setSystemUiVisibility(darkMode
                ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    private View createContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(backgroundColor());
        root.setPadding(dp(20), dp(12), dp(20), dp(8));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int topInset = insets.getSystemWindowInsetTop();
            int bottomInset = insets.getSystemWindowInsetBottom();
            if (topInset <= 0) {
                topInset = systemBarHeight("status_bar_height");
            }
            view.setPadding(dp(20), topInset + dp(12), dp(20), bottomInset + dp(8));
            return insets;
        });

        root.addView(createHeader(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scrollView = new ScrollView(this);
        scrollView.setClipToPadding(false);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scrollView.setPadding(0, dp(18), 0, dp(24));
        cardList = new LinearLayout(this);
        cardList.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(cardList, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.post(root::requestApplyInsets);
        return root;
    }

    private View createHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleColumn = new LinearLayout(this);
        titleColumn.setOrientation(LinearLayout.VERTICAL);
        TextView title = textView("负一屏扩展", 28, primaryColor());
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleColumn.addView(title);
        header.addView(titleColumn, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f));

        themeButton = new ImageButton(this);
        themeButton.setScaleType(ImageView.ScaleType.CENTER);
        themeButton.setPadding(dp(10), dp(10), dp(10), dp(10));
        themeButton.setImageResource(darkMode
                ? R.drawable.ic_theme_sun
                : R.drawable.ic_theme_moon);
        themeButton.setContentDescription(getString(darkMode
                ? R.string.theme_to_light
                : R.string.theme_to_dark));
        themeButton.setBackground(round(toggleSurfaceColor(), dp(16)));
        themeButton.setOnClickListener(view -> toggleTheme(themeButton));
        header.addView(themeButton, fixedMarginParams(dp(44), dp(44), 12, 0, 0, 0));
        return header;
    }

    private void toggleTheme(ImageButton button) {
        if (themeTransitionRunning) {
            return;
        }
        themeTransitionRunning = true;
        boolean targetDark = !darkMode;
        int direction = targetDark ? -1 : 1;
        int startBackground = backgroundColor();
        int targetBackground = targetDark ? DARK_BACKGROUND : LIGHT_BACKGROUND;
        FrameLayout contentFrame = (FrameLayout) findViewById(android.R.id.content);
        View cover = new View(this);
        cover.setBackgroundColor(targetBackground);
        cover.setAlpha(0f);
        cover.setClickable(true);
        contentFrame.addView(cover, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        button.animate()
                .translationX(direction * dp(12))
                .alpha(0f)
                .setDuration(220L)
                .start();

        ValueAnimator fadeIn = ValueAnimator.ofFloat(0f, 1f);
        fadeIn.setDuration(320L);
        fadeIn.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            cover.setAlpha(progress);
            updateWindowColors(startBackground, targetBackground, progress);
        });
        fadeIn.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                darkMode = targetDark;
                getSharedPreferences(UI_PREFERENCES, MODE_PRIVATE)
                        .edit()
                        .putBoolean(DARK_MODE_KEY, darkMode)
                        .apply();
                int scrollY = scrollView == null ? 0 : scrollView.getScrollY();
                configureWindow();
                setContentView(createContentView());
                renderCatalog();
                if (scrollView != null) {
                    scrollView.post(() -> scrollView.scrollTo(0, scrollY));
                }
                revealTheme(targetBackground, direction);
            }
        });
        fadeIn.start();
    }

    private void revealTheme(int targetBackground, int direction) {
        FrameLayout contentFrame = (FrameLayout) findViewById(android.R.id.content);
        View cover = new View(this);
        cover.setBackgroundColor(targetBackground);
        cover.setAlpha(1f);
        cover.setClickable(true);
        contentFrame.addView(cover, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        if (themeButton != null) {
            themeButton.setTranslationX(-direction * dp(12));
            themeButton.setAlpha(0f);
        }
        ValueAnimator fadeOut = ValueAnimator.ofFloat(1f, 0f);
        fadeOut.setDuration(260L);
        fadeOut.addUpdateListener(animation -> cover.setAlpha(
                (float) animation.getAnimatedValue()));
        fadeOut.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                contentFrame.removeView(cover);
                if (themeButton != null) {
                    themeButton.animate()
                            .translationX(0f)
                            .alpha(1f)
                            .setDuration(220L)
                            .start();
                }
                themeTransitionRunning = false;
            }
        });
        fadeOut.start();
    }

    private void updateWindowColors(int startColor, int targetColor, float progress) {
        int color = blendColor(startColor, targetColor, progress);
        getWindow().setStatusBarColor(color);
        getWindow().setNavigationBarColor(color);
    }

    private int blendColor(int from, int to, float progress) {
        float amount = Math.max(0f, Math.min(1f, progress));
        int red = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * amount);
        int green = Math.round(Color.green(from)
                + (Color.green(to) - Color.green(from)) * amount);
        int blue = Math.round(Color.blue(from)
                + (Color.blue(to) - Color.blue(from)) * amount);
        return Color.rgb(red, green, blue);
    }

    private void loadCatalog() {
        if (loading) {
            return;
        }
        loading = true;
        catalogExecutor.execute(() -> {
            List<CardCatalog.AppGroup> loaded;
            try {
                loaded = CardCatalog.load(getApplicationContext());
            } catch (Throwable ignored) {
                loaded = java.util.Collections.emptyList();
            }
            List<CardCatalog.AppGroup> result = loaded;
            runOnUiThread(() -> {
                loading = false;
                catalog = result;
                disabledCardIds.clear();
                disabledCardIds.addAll(CardControlProvider.readDisabled(this));
                renderCatalog();
            });
        });
    }

    private void renderCatalog() {
        if (cardList == null) {
            return;
        }
        cardList.removeAllViews();
        int visibleCount = 0;
        for (CardCatalog.AppGroup group : catalog) {
            if (group.cards.isEmpty()) {
                continue;
            }
            visibleCount += group.cards.size();
            cardList.addView(createGroupView(group), marginParams(0, 0, 0, 12));
        }
        if (visibleCount == 0) {
            cardList.addView(createEmptyView());
        }
    }

    private View createGroupView(CardCatalog.AppGroup group) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(16), dp(14), dp(16), dp(14));
        container.setBackground(round(surfaceColor(), dp(18)));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView appIcon = iconView(group.icon, dp(46), dp(14));
        header.addView(appIcon, fixedMarginParams(dp(46), dp(46), 0, 0, 12, 0));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        TextView appName = textView(group.label, 16, primaryColor());
        appName.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        appName.setIncludeFontPadding(false);
        TextView count = textView(group.cards.size() + " 张卡片", 12, secondaryColor());
        count.setIncludeFontPadding(false);
        labels.addView(appName);
        labels.addView(count, marginParams(0, 3, 0, 0));
        header.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        boolean expanded = expandedGroups.getOrDefault(group.key, false);
        container.addView(header);

        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setVisibility(expanded ? View.VISIBLE : View.GONE);
        for (CardCatalog.Card card : group.cards) {
            rows.addView(createCardRow(card));
        }
        container.addView(rows, marginParams(0, 8, 0, 0));
        header.setOnClickListener(view -> toggleGroup(group.key, rows));
        return container;
    }

    private View createCardRow(CardCatalog.Card card) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(68));
        row.setPadding(0, dp(6), 0, dp(6));

        ImageView cardIcon = iconView(card.icon, dp(34), dp(10));
        row.addView(cardIcon, fixedMarginParams(dp(34), dp(34), 0, 0, 12, 0));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = textView(card.title, 14, primaryColor());
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setMaxLines(1);
        title.setIncludeFontPadding(false);
        TextView subtitle = textView(card.subtitle, 11, secondaryColor());
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        subtitle.setMaxLines(1);
        subtitle.setIncludeFontPadding(false);
        labels.addView(title);
        labels.addView(subtitle, marginParams(0, 3, 0, 0));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));

        Switch toggle = new Switch(this);
        toggle.setShowText(false);
        toggle.setChecked(card.enabled);
        styleSwitch(toggle);
        toggle.setOnCheckedChangeListener((button, enabled) -> onCardToggled(card, enabled));
        row.addView(toggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setOnClickListener(view -> toggle.performClick());
        return row;
    }

    private void toggleGroup(String key, View rows) {
        boolean expand = rows.getVisibility() != View.VISIBLE;
        expandedGroups.put(key, expand);
        if (expand) {
            rows.setVisibility(View.VISIBLE);
            rows.setAlpha(0f);
            rows.setTranslationY(-dp(8));
            rows.animate().alpha(1f).translationY(0f).setDuration(180).start();
        } else {
            rows.animate().alpha(0f).translationY(-dp(8)).setDuration(150)
                    .withEndAction(() -> {
                        rows.setVisibility(View.GONE);
                        rows.setAlpha(1f);
                        rows.setTranslationY(0f);
                    })
                    .start();
        }
    }

    private void onCardToggled(CardCatalog.Card card, boolean enabled) {
        card.enabled = enabled;
        if (enabled) {
            disabledCardIds.remove(card.id);
        } else {
            disabledCardIds.add(card.id);
        }
        CardControlProvider.writeDisabled(this, disabledCardIds);
        CardControlProvider.requestRefresh(this);
    }

    private View createEmptyView() {
        LinearLayout empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(24), dp(56), dp(24), dp(56));
        empty.setBackground(round(surfaceColor(), dp(18)));
        TextView title = textView("还没有第三方卡片", 16, primaryColor());
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        TextView hint = textView(
                "安装提供桌面小组件的应用后会自动出现在这里",
                12,
                secondaryColor());
        hint.setGravity(Gravity.CENTER);
        empty.addView(title);
        empty.addView(hint, marginParams(0, 8, 0, 0));
        return empty;
    }

    private ImageView iconView(Drawable drawable, int size, int radius) {
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        image.setPadding(dp(5), dp(5), dp(5), dp(5));
        image.setBackground(round(iconSurfaceColor(), radius));
        image.setClipToOutline(true);
        if (drawable != null) {
            image.setImageDrawable(drawable);
        } else {
            image.setImageResource(android.R.drawable.sym_def_app_icon);
        }
        return image;
    }

    private void styleSwitch(Switch toggle) {
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{}
        };
        toggle.setThumbTintList(new ColorStateList(states, new int[]{
                accentColor(),
                darkMode ? Color.rgb(101, 116, 122) : Color.rgb(191, 201, 204)
        }));
        toggle.setTrackTintList(new ColorStateList(states, new int[]{
                darkMode ? Color.rgb(57, 116, 103) : Color.rgb(177, 221, 205),
                darkMode ? Color.rgb(65, 78, 84) : Color.rgb(220, 226, 228)
        }));
    }

    private int backgroundColor() {
        return darkMode ? DARK_BACKGROUND : LIGHT_BACKGROUND;
    }

    private int surfaceColor() {
        return darkMode ? DARK_SURFACE : LIGHT_SURFACE;
    }

    private int primaryColor() {
        return darkMode ? DARK_PRIMARY : LIGHT_PRIMARY;
    }

    private int secondaryColor() {
        return darkMode ? DARK_SECONDARY : LIGHT_SECONDARY;
    }

    private int iconSurfaceColor() {
        return darkMode ? DARK_ICON_SURFACE : LIGHT_ICON_SURFACE;
    }

    private int toggleSurfaceColor() {
        return darkMode ? DARK_TOGGLE_SURFACE : LIGHT_TOGGLE_SURFACE;
    }

    private int accentColor() {
        return darkMode ? DARK_ACCENT : LIGHT_ACCENT;
    }

    private TextView textView(String value, float sizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        return view;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private int systemBarHeight(String resourceName) {
        int resourceId = getResources().getIdentifier(resourceName, "dimen", "android");
        return resourceId == 0 ? 0 : getResources().getDimensionPixelSize(resourceId);
    }

    private LinearLayout.LayoutParams marginParams(
            int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private LinearLayout.LayoutParams fixedMarginParams(
            int width,
            int height,
            int left,
            int top,
            int right,
            int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
