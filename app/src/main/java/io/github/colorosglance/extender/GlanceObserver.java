package io.github.colorosglance.extender;

import android.annotation.SuppressLint;
import android.appwidget.AppWidgetProviderInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * ColorOS 速览标准 AppWidget 通用兼容入口。
 *
 * <p>本版本在 UMS 自然重建配置时临时加入当前用户可见的标准 AppWidget 配置，
 * 原方法完成后立即从云配置事实源移除，绝不主动写 DAO、通知重放或操作 AppWidget ID。</p>
 */
public final class GlanceObserver extends XposedModule {
    private static final String TAG = "ColorOSGlanceExtender";
    private static final String ASSISTANT_PACKAGE = "com.coloros.assistantscreen";
    private static final String UMS_PACKAGE = "com.oplus.pantanal.ums";
    private static final int SYNTHETIC_TYPE = 0x43474557;
    private static final int SYNTHETIC_TYPE_PREFIX = 0x60000000;
    private static final int SYNTHETIC_TYPE_MASK = 0x1fffffff;
    private static final int SYNTHETIC_GROUP_PREFIX = 0x20000000;
    private static final int SYNTHETIC_GROUP_MASK = 0x1fffffff;
    private static final int OPERATION_WIDGET_CATEGORY = 5;
    private static final int SYNTHETIC_GROUP_ORDER_BASE = 100_000;
    private static final int SYNTHETIC_PRIORITY = 999_999;
    private static final int MAX_SYNTHETIC_CONFIGS = 512;
    private static final int MAX_RESIDENT_SCAN = 4096;
    private static final String ICON_PROVIDER_AUTHORITY =
            "io.github.colorosglance.extender.icons";
    private static final String ICON_PROVIDER_APP_PATH = "app";
    private static final String MARKER_KEY =
            "io.github.colorosglance.extender.synthetic_owner";
    private static final String MARKER_VALUE = "standard_appwidget_v1";
    private static final String MARKER_COMPONENT_KEY =
            "io.github.colorosglance.extender.synthetic_component";
    private static final Uri ASSISTANT_PROVIDER_URI =
            Uri.parse("content://com.oplus.assistantscreen.provider");
    private static final String METHOD_QUERY_ADD_CARD_STATE = "queryAddCardState";
    private static final String PARAM_CARD_TYPE = "cardType";
    private static final String PARAM_ADD_CARD_TO = "addCardTo";
    private static final int ADD_CARD_TO_ASSISTANT_SCREEN = 1;
    private static final int RESIDENT_BRIDGE_VERSION = 2;
    private static final String RESIDENT_BRIDGE_PREFIX =
            "io.github.colorosglance.extender.resident_bridge.";
    private static final String BRIDGE_VERSION_KEY = RESIDENT_BRIDGE_PREFIX + "version";
    private static final String BRIDGE_KNOWN_KEY = RESIDENT_BRIDGE_PREFIX + "known";
    private static final String BRIDGE_READY_KEY = RESIDENT_BRIDGE_PREFIX + "ready";
    private static final String BRIDGE_SIZE_KEY = RESIDENT_BRIDGE_PREFIX + "size";
    private static final String BRIDGE_CONTROL_TYPE_KEY =
            RESIDENT_BRIDGE_PREFIX + "control_type";
    private static final String BRIDGE_NULL_PROVIDER_KEY =
            RESIDENT_BRIDGE_PREFIX + "null_provider";
    private static final String BRIDGE_COMPONENT_PROVIDER_KEY =
            RESIDENT_BRIDGE_PREFIX + "component_provider";
    private static final String BRIDGE_MALFORMED_KEY =
            RESIDENT_BRIDGE_PREFIX + "malformed";
    private static final String BRIDGE_ELAPSED_REALTIME_KEY =
            RESIDENT_BRIDGE_PREFIX + "elapsed_realtime";

    private final AtomicBoolean hooksInstalled = new AtomicBoolean(false);
    private final AtomicLong preflightSequence = new AtomicLong(0);
    private final AtomicReference<WeakReference<Object>> subscriptionService =
            new AtomicReference<>(new WeakReference<>(null));
    private final AtomicReference<List<AppWidgetProviderInfo>> latestProviderInfos =
            new AtomicReference<>(Collections.emptyList());
    private final AtomicReference<WeakReference<Object>> configRepository =
            new AtomicReference<>(new WeakReference<>(null));
    private final AtomicBoolean refreshReceiverRegistered = new AtomicBoolean(false);
    private final AtomicBoolean refreshInProgress = new AtomicBoolean(false);
    private final AtomicLong lastCatalogPublish = new AtomicLong(0L);
    private volatile Set<String> disabledCardIds = Collections.emptySet();
    private volatile long disabledCardIdsReadAt;
    private volatile BroadcastReceiver refreshReceiver;
    private volatile Constructor<?> operationWidgetConfigConstructor;
    private volatile Method operationWidgetToRawMethod;
    private volatile ResidentLayout residentLayout;
    private volatile String loadedProcessName = "unknown";

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        loadedProcessName = safeText(param.getProcessName(), 160);
        info("模块已加载：process=" + loadedProcessName
                + ", framework=" + getFrameworkName()
                + ", api=" + getApiVersion());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }
        installHooksIfReady(
                param.getPackageName(),
                param.isFirstPackage(),
                param.getDefaultClassLoader(),
                "onPackageLoaded");
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        installHooksIfReady(
                param.getPackageName(),
                param.isFirstPackage(),
                param.getClassLoader(),
                "onPackageReady");
    }

    private void installHooksIfReady(
            String packageName,
            boolean firstPackage,
            ClassLoader classLoader,
            String lifecycle) {
        if ((!ASSISTANT_PACKAGE.equals(packageName) && !UMS_PACKAGE.equals(packageName))
                || !firstPackage) {
            return;
        }
        if (!packageName.equals(loadedProcessName)) {
            info(lifecycle + "：跳过非主进程；package=" + packageName
                    + ", process=" + loadedProcessName);
            return;
        }
        if (!hasTargetClasses(packageName, classLoader)) {
            info(lifecycle + "：核心类尚不可用，等待后续生命周期回调；package="
                    + packageName);
            return;
        }
        if (!hooksInstalled.compareAndSet(false, true)) {
            return;
        }

        info(lifecycle + "：开始安装核心 Hook；package=" + packageName
                + ", classLoader=" + classLoader);
        if (UMS_PACKAGE.equals(packageName)) {
            hookUmsInjection(classLoader);
            info("UMS 动态注入 Hook 安装完成");
            return;
        }

        hookResidentStateQuery(classLoader);
        info("速览 resident bridge Hook 安装完成");
    }

    private boolean hasTargetClasses(String packageName, ClassLoader classLoader) {
        try {
            if (UMS_PACKAGE.equals(packageName)) {
                Class.forName(
                        "com.oplus.ums.card.configuration.widget.repository.e",
                        false,
                        classLoader);
                Class.forName(
                        "com.oplus.ums.card.configuration.widget.repository."
                                + "OperationWidgetConfigRepository",
                        false,
                        classLoader);
            } else {
                Class.forName(
                        "com.oplus.assistantscreen.card.store.provider."
                                + "AssistantContentProvider",
                        false,
                        classLoader);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void hookResidentStateQuery(ClassLoader classLoader) {
        String providerClass =
                "com.oplus.assistantscreen.card.store.provider.AssistantContentProvider";
        hookMethod(classLoader, providerClass, METHOD_QUERY_ADD_CARD_STATE,
                new Class<?>[]{Bundle.class}, chain -> {
                    Object request = chain.getArg(0);
                    boolean bridgeRequest = isResidentBridgeRequest(request);
                    int callingUid = Binder.getCallingUid();
                    boolean umsCaller = bridgeRequest && isPackageForUid(
                            chain.getThisObject(), callingUid, UMS_PACKAGE);
                    Object result = proceedUnchanged(
                            chain, "resident-bridge-query", bridgeRequest);
                    if (!bridgeRequest) {
                        return result;
                    }

                    Object service = captureResidentService(chain.getThisObject());
                    Object returnedResult = umsCaller
                            ? addResidentBridgeState(result, service)
                            : result;
                    observeSafely("AssistantContentProvider#queryAddCardState", () -> preflight(
                            "resident bridge: callerUid=" + callingUid
                                    + ", umsCaller=" + umsCaller
                                    + ", service=" + summarizeResident(service)
                                    + ", request=" + summarizeResidentBridgeRequest(request)
                                    + ", returned=" + summarizeResidentBridge(returnedResult)));
                    return returnedResult;
                });
    }

    private Object captureResidentService(Object provider) {
        Object service = null;
        try {
            service = invokeNoArg(provider, "getCardSubscriptionService");
        } catch (Throwable ignored) {
            try {
                for (Method method : provider.getClass().getMethods()) {
                    if (method.getParameterTypes().length != 0
                            || method.getReturnType() == Void.TYPE) {
                        continue;
                    }
                    String methodName = method.getName();
                    String returnType = method.getReturnType().getName();
                    if (!methodName.contains("Subscription")
                            && !returnType.contains("CardFacade")
                            && !returnType.contains("CardSubscription")) {
                        continue;
                    }
                    method.setAccessible(true);
                    service = method.invoke(provider);
                    if (service != null) {
                        break;
                    }
                }
            } catch (Throwable ignoredFallback) {
            }
        }
        if (service != null) {
            subscriptionService.set(new WeakReference<>(service));
            return service;
        }
        return subscriptionService.get().get();
    }

    private void hookUmsInjection(ClassLoader classLoader) {
        String providerRepository =
                "com.oplus.ums.card.configuration.widget.repository.e";
        String configRepositoryClass =
                "com.oplus.ums.card.configuration.widget.repository."
                        + "OperationWidgetConfigRepository";

        registerRefreshReceiver();

        hookMethod(classLoader, providerRepository, "a", new Class<?>[0], chain -> {
            registerRefreshReceiver();
            Object result = proceedUnchanged(chain, "ums-provider-snapshot", true);
            observeSafely("UMS provider snapshot", () -> {
                List<AppWidgetProviderInfo> snapshot = snapshotProviderInfos(result);
                latestProviderInfos.set(snapshot);
                preflight("UMS Provider 快照：size=" + snapshot.size());
            });
            return result;
        });

        hookMethod(classLoader, configRepositoryClass, "c", new Class<?>[]{List.class}, chain -> {
            registerRefreshReceiver();
            configRepository.set(new WeakReference<>(chain.getThisObject()));
            Object input = chain.getArg(0);
            long started = SystemClock.elapsedRealtime();
            SyntheticTrigger trigger = prepareSyntheticTrigger(
                    classLoader, chain.getThisObject(), input, latestProviderInfos.get());
            Object effectiveInput = trigger.augmentedInput != null
                    ? trigger.augmentedInput
                    : input;
            Object result = effectiveInput == input
                    ? proceedUnchanged(chain, "ums-config-repository-update", true)
                    : proceedWithArgs(
                            chain,
                            "ums-config-repository-update",
                            new Object[]{effectiveInput},
                            true);
            publishRepositoryCatalog(chain.getThisObject());
            observeSafely("UMS config repository update", () -> preflight(
                    "OperationWidgetConfigRepository#c: durationMs="
                            + (SystemClock.elapsedRealtime() - started)
                            + ", input=" + summarizeRawConfigs(input)
                            + ", output=" + summarizeRawConfigs(result)
                            + ", trigger={" + trigger.summary + "}"));
            return result;
        });

        hookMethod(classLoader, configRepositoryClass, "d", new Class<?>[]{boolean.class}, chain -> {
            registerRefreshReceiver();
            Object repository = chain.getThisObject();
            configRepository.set(new WeakReference<>(repository));
            synchronized (repository) {
                long started = SystemClock.elapsedRealtime();
                SyntheticInjection injection = prepareSyntheticInjection(
                        classLoader, repository, latestProviderInfos.get());
                DisabledConfigRemoval disabledRemoval =
                        prepareDisabledConfigRemoval(repository);
                try {
                    Object result = proceedUnchanged(chain, "ums-config-rebuild", true);
                    publishRepositoryCatalog(repository);
                    observeSafely("UMS config rebuild", () -> preflight(
                            "OperationWidgetConfigRepository#d: durationMs="
                                    + (SystemClock.elapsedRealtime() - started)
                                    + ", notify=" + chain.getArg(0)
                                    + ", injection={" + injection.summary + "}"
                                    + ", repository={" + summarizeUmsRepository(repository)
                                    + "}"));
                    return result;
                } finally {
                    cleanupSyntheticInjection(repository, injection);
                    restoreDisabledConfigRemoval(repository, disabledRemoval);
                }
            }
        });
    }

    private SyntheticTrigger prepareSyntheticTrigger(
            ClassLoader classLoader,
            Object repository,
            Object input,
            List<AppWidgetProviderInfo> fallbackProviders) {
        if (!(input instanceof List<?>)) {
            return SyntheticTrigger.none("active=false, reason=input-structure-mismatch");
        }
        try {
            for (Object item : (List<?>) input) {
                if (isSyntheticConfig(item)) {
                    return SyntheticTrigger.none(
                            "active=false, reason=marker-already-present");
                }
            }
            List<AppWidgetProviderInfo> providers = resolveProviderInfos(
                    repository, fallbackProviders);
            SyntheticBuild build = buildSyntheticConfigs(
                    classLoader, input, providers, 1, true);
            if (build.configs.isEmpty()) {
                return SyntheticTrigger.none(build.summary);
            }
            Object rawConfig = convertOperationWidgetToRaw(
                    classLoader, build.configs.get(0));
            ArrayList<Object> augmented = new ArrayList<>((List<?>) input);
            augmented.add(rawConfig);
            return new SyntheticTrigger(
                    augmented,
                    "active=true, added=1, " + build.summary);
        } catch (Throwable throwable) {
            error("UMS 通用 AppWidget 触发配置构建失败；保持原输入", throwable);
            return SyntheticTrigger.none(
                    "active=false, reason=build-failed, error="
                            + summarizeThrowable(throwable));
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private SyntheticInjection prepareSyntheticInjection(
            ClassLoader classLoader,
            Object repository,
            List<AppWidgetProviderInfo> fallbackProviders) {
        try {
            Object source = readField(repository, "g");
            if (!(source instanceof List<?>)) {
                return SyntheticInjection.none(
                        "active=false, reason=repository-structure-mismatch");
            }
            List<AppWidgetProviderInfo> providers = resolveProviderInfos(
                    repository, fallbackProviders);
            SyntheticBuild build = buildSyntheticConfigs(
                    classLoader, source, providers, MAX_SYNTHETIC_CONFIGS, false);
            if (build.configs.isEmpty()) {
                return SyntheticInjection.none(build.summary);
            }
            ((List) source).addAll(build.configs);
            return new SyntheticInjection(
                    build.configs,
                    "active=true, added=" + build.configs.size() + ", " + build.summary);
        } catch (Throwable throwable) {
            error("UMS 通用 AppWidget 注入准备失败；本次重建保持原样", throwable);
            return SyntheticInjection.none(
                    "active=false, reason=prepare-failed, error="
                            + summarizeThrowable(throwable));
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void cleanupSyntheticInjection(Object repository, SyntheticInjection injection) {
        try {
            Object source = readField(repository, "g");
            if (!(source instanceof List<?>)) {
                error("UMS 通用 AppWidget 临时配置清理失败：repository.g 结构不匹配",
                        new IllegalStateException(classNameOf(source)));
                return;
            }
            ArrayList<Object> synthetic = new ArrayList<>();
            for (Object item : (List<?>) source) {
                if (isSyntheticConfig(item)) {
                    synthetic.add(item);
                }
            }
            if (!synthetic.isEmpty()) {
                ((List) source).removeAll(synthetic);
            }
            preflight("UMS synthetic cleanup: removed=" + synthetic.size()
                    + ", expectedAdded=" + injection.added.size()
                    + ", cloudSizeAfter=" + ((List<?>) source).size()
                    + ", persistentWrite=false");
        } catch (Throwable throwable) {
            error("UMS 通用 AppWidget 临时配置清理失败", throwable);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private DisabledConfigRemoval prepareDisabledConfigRemoval(Object repository) {
        Set<String> disabled = readDisabledCardIds();
        if (disabled.isEmpty()) {
            return DisabledConfigRemoval.none();
        }
        try {
            Object source = readField(repository, "g");
            if (!(source instanceof List<?>)) {
                return DisabledConfigRemoval.none();
            }
            List sourceList = (List) source;
            ArrayList<RemovedConfig> removed = new ArrayList<>();
            for (int index = sourceList.size() - 1; index >= 0; index--) {
                Object item = sourceList.get(index);
                if (isSyntheticConfig(item)) {
                    continue;
                }
                String identity = cardIdentity(item);
                if (!identity.isEmpty() && disabled.contains(identity)) {
                    removed.add(new RemovedConfig(index, item));
                    sourceList.remove(index);
                }
            }
            if (removed.isEmpty()) {
                return DisabledConfigRemoval.none();
            }
            Collections.sort(removed, Comparator.comparingInt(value -> value.index));
            preflight("UMS disabled card filter: removed=" + removed.size());
            return new DisabledConfigRemoval(removed);
        } catch (Throwable throwable) {
            error("UMS disabled card filter failed; keep original config", throwable);
            return DisabledConfigRemoval.none();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void restoreDisabledConfigRemoval(
            Object repository,
            DisabledConfigRemoval removal) {
        if (removal.removed.isEmpty()) {
            return;
        }
        try {
            Object source = readField(repository, "g");
            if (!(source instanceof List<?>)) {
                return;
            }
            List sourceList = (List) source;
            for (RemovedConfig entry : removal.removed) {
                if (sourceList.contains(entry.value)) {
                    continue;
                }
                int targetIndex = Math.min(entry.index, sourceList.size());
                sourceList.add(targetIndex, entry.value);
            }
            preflight("UMS disabled card restore: restored=" + removal.removed.size());
        } catch (Throwable throwable) {
            error("UMS disabled card restore failed", throwable);
        }
    }

    private Set<String> readDisabledCardIds() {
        long now = SystemClock.elapsedRealtime();
        if (now - disabledCardIdsReadAt < 500L) {
            return disabledCardIds;
        }
        Context context = findApplicationContext();
        if (context == null) {
            return disabledCardIds;
        }
        try {
            Bundle result = context.getContentResolver().call(
                    ModuleBridge.CONTROL_URI,
                    ModuleBridge.METHOD_GET_DISABLED,
                    null,
                    null);
            ArrayList<String> values = result == null
                    ? null
                    : result.getStringArrayList(ModuleBridge.KEY_DISABLED_IDS);
            if (values == null) {
                return disabledCardIds;
            }
            HashSet<String> copy = new HashSet<>();
            for (String value : values) {
                if (value != null && !value.isEmpty() && value.length() <= 512) {
                    copy.add(value);
                }
            }
            disabledCardIds = Collections.unmodifiableSet(copy);
            disabledCardIdsReadAt = now;
            return disabledCardIds;
        } catch (Throwable throwable) {
            error("读取卡片启用状态失败；沿用最近一次状态", throwable);
            return disabledCardIds;
        }
    }

    private static String cardIdentity(Object config) {
        try {
            String extras = asString(invokeNoArg(config, "getExtras"));
            if (extras != null && !extras.isEmpty()) {
                JSONObject marker = new JSONObject(extras);
                String markedComponent = marker.optString(MARKER_COMPONENT_KEY, "");
                if (!markedComponent.isEmpty()) {
                    return markedComponent;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            String packageName = asString(invokeNoArg(config, "getPackageName"));
            String componentName = asString(invokeNoArg(config, "getComponentName"));
            if (packageName != null && !packageName.isEmpty()
                    && componentName != null && !componentName.isEmpty()) {
                return providerComponentKey(packageName, componentName);
            }
            Integer type = asInteger(invokeNoArg(config, "getType"));
            return type == null ? "" : "type:" + type;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private SyntheticBuild buildSyntheticConfigs(
            ClassLoader classLoader,
            Object existingConfigValue,
            List<AppWidgetProviderInfo> providerValue,
            int outputLimit,
            boolean triggerOnly) throws Throwable {
        Bundle bridge = queryResidentBridgeOnce();
        String bridgeSummary = summarizeResidentBridge(bridge);
        if (!isResidentBridgeReady(bridge)) {
            return SyntheticBuild.empty(
                    "bridgeReady=false, bridge={" + bridgeSummary + "}");
        }
        if (!(existingConfigValue instanceof List<?>)) {
            return SyntheticBuild.empty(
                    "bridgeReady=true, reason=config-structure-mismatch");
        }

        List<AppWidgetProviderInfo> providers = providerValue == null
                ? Collections.emptyList()
                : providerValue;
        Set<String> disabled = readDisabledCardIds();
        ArrayList<AppWidgetProviderInfo> sortedProviders = new ArrayList<>(providers);
        sortedProviders.sort(Comparator.comparing(GlanceObserver::providerComponentKey));

        Set<Integer> usedTypes = new HashSet<>();
        Set<Integer> usedGroupIds = new HashSet<>();
        Set<String> configuredComponents = new HashSet<>();
        Map<String, Integer> packageGroupIds = new HashMap<>();
        Map<String, Integer> packageGroupOrders = new HashMap<>();
        Map<String, Integer> packageNextOrders = new HashMap<>();
        Map<String, String> packageGroupTitles = new HashMap<>();
        Map<String, String> packageGroupIcons = new HashMap<>();
        Set<String> localIconPackages = new HashSet<>();
        Set<String> missingIconPackages = new HashSet<>();
        Set<String> localTitlePackages = new HashSet<>();
        Set<String> missingTitlePackages = new HashSet<>();
        int existingSynthetic = 0;
        int malformedConfig = 0;
        for (Object item : (List<?>) existingConfigValue) {
            try {
                Integer type = asInteger(invokeNoArg(item, "getType"));
                Integer groupId = asInteger(invokeNoArg(item, "getGroupId"));
                Integer groupOrder = asInteger(invokeNoArg(item, "getGroupOrder"));
                Integer orderInGroup = asInteger(invokeNoArg(item, "getOrderInGroup"));
                String packageName = asString(invokeNoArg(item, "getPackageName"));
                String componentName = asString(invokeNoArg(item, "getComponentName"));
                String groupTitle = asString(invokeNoArg(item, "getGroupTitle"));
                String groupIcon = asString(invokeNoArg(item, "getGroupIcon"));
                if (type != null) {
                    usedTypes.add(type);
                }
                if (groupId != null) {
                    usedGroupIds.add(groupId);
                }
                if (packageName != null && !packageName.isEmpty()) {
                    if (groupId != null && groupId >= 0) {
                        packageGroupIds.putIfAbsent(packageName, groupId);
                    }
                    if (groupOrder != null && groupOrder >= 0) {
                        packageGroupOrders.putIfAbsent(packageName, groupOrder);
                    }
                    if (groupTitle != null && !groupTitle.isEmpty()) {
                        packageGroupTitles.putIfAbsent(packageName, groupTitle);
                    }
                    if (groupIcon != null && !groupIcon.isEmpty()) {
                        packageGroupIcons.putIfAbsent(packageName, groupIcon);
                    }
                    if (orderInGroup != null) {
                        packageNextOrders.merge(
                                packageName, orderInGroup + 1, Math::max);
                    }
                    if (componentName != null && !componentName.isEmpty()) {
                        configuredComponents.add(
                                providerComponentKey(packageName, componentName));
                    }
                }
                if (isSyntheticConfig(item)) {
                    existingSynthetic++;
                }
            } catch (Throwable ignored) {
                malformedConfig++;
            }
        }

        int existingIconPackages = packageGroupIcons.size();
        int existingTitlePackages = packageGroupTitles.size();

        ArrayList<AppWidgetProviderInfo> candidates = new ArrayList<>();
        Set<String> seenProviders = new HashSet<>();
        int foreignProfileSkipped = 0;
        int nonHomeSkipped = 0;
        int configuredSkipped = 0;
        int disabledSkipped = 0;
        int duplicateSkipped = 0;
        int malformedProvider = 0;
        for (AppWidgetProviderInfo info : sortedProviders) {
            try {
                if (info == null || info.provider == null) {
                    malformedProvider++;
                    continue;
                }
                UserHandle profile = info.getProfile();
                if (profile != null && !Process.myUserHandle().equals(profile)) {
                    foreignProfileSkipped++;
                    continue;
                }
                if (info.widgetCategory != 0
                        && (info.widgetCategory
                        & AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) == 0) {
                    nonHomeSkipped++;
                    continue;
                }
                String componentKey = providerComponentKey(info);
                if (!seenProviders.add(componentKey)) {
                    duplicateSkipped++;
                    continue;
                }
                if (configuredComponents.contains(componentKey)) {
                    configuredSkipped++;
                    continue;
                }
                if (disabled.contains(componentKey)) {
                    disabledSkipped++;
                    continue;
                }
                candidates.add(info);
            } catch (Throwable ignored) {
                malformedProvider++;
            }
        }
        if (!triggerOnly && candidates.size() > MAX_SYNTHETIC_CONFIGS) {
            return SyntheticBuild.empty(
                    "bridgeReady=true, providers=" + providers.size()
                            + ", candidates=" + candidates.size()
                            + ", reason=limit-exceeded");
        }

        int generationLimit = Math.min(outputLimit, candidates.size());
        ArrayList<Object> configs = new ArrayList<>(generationLimit);
        int typeCollisionSkipped = 0;
        int constructorFailures = 0;
        int configuredActivities = 0;
        int sizeOne = 0;
        int sizeTwo = 0;
        int sizeThree = 0;
        for (int index = 0; index < generationLimit; index++) {
            AppWidgetProviderInfo info = candidates.get(index);
            ComponentName provider = info.provider;
            String packageName = provider.getPackageName();
            String componentName = provider.getClassName();
            String componentKey = providerComponentKey(packageName, componentName);
            int type = allocateStableId(
                    componentKey, SYNTHETIC_TYPE_PREFIX, SYNTHETIC_TYPE_MASK, usedTypes);
            if (type == Integer.MIN_VALUE) {
                typeCollisionSkipped++;
                continue;
            }
            Integer groupId = packageGroupIds.get(packageName);
            if (groupId == null) {
                int allocatedGroupId = allocateStableId(
                        packageName,
                        SYNTHETIC_GROUP_PREFIX,
                        SYNTHETIC_GROUP_MASK,
                        usedGroupIds);
                if (allocatedGroupId == Integer.MIN_VALUE) {
                    typeCollisionSkipped++;
                    continue;
                }
                groupId = allocatedGroupId;
                packageGroupIds.put(packageName, groupId);
            }
            int groupOrder = packageGroupOrders.computeIfAbsent(
                    packageName,
                    ignored -> SYNTHETIC_GROUP_ORDER_BASE
                            + (stableHash(packageName) & 0x7fff));
            int orderInGroup = packageNextOrders.getOrDefault(packageName, 0);
            packageNextOrders.put(packageName, orderInGroup + 1);
            int size = classifyWidgetSize(info);
            if (size == 1) {
                sizeOne++;
            } else if (size == 2) {
                sizeTwo++;
            } else {
                sizeThree++;
            }
            if (info.configure != null) {
                configuredActivities++;
            }
            String groupTitle = packageGroupTitles.getOrDefault(packageName, "");
            if (groupTitle.isEmpty()) {
                groupTitle = resolveLocalGroupTitle(info);
                if (groupTitle.isEmpty()) {
                    missingTitlePackages.add(packageName);
                } else {
                    packageGroupTitles.put(packageName, groupTitle);
                    localTitlePackages.add(packageName);
                    missingTitlePackages.remove(packageName);
                }
            }
            String groupIcon = packageGroupIcons.getOrDefault(packageName, "");
            if (groupIcon.isEmpty()) {
                groupIcon = resolveLocalGroupIcon(info);
                if (groupIcon.isEmpty()) {
                    missingIconPackages.add(packageName);
                } else {
                    packageGroupIcons.put(packageName, groupIcon);
                    localIconPackages.add(packageName);
                    missingIconPackages.remove(packageName);
                }
            }
            try {
                configs.add(createOperationWidgetConfig(
                        classLoader,
                        info,
                        groupId,
                        groupTitle,
                        groupIcon,
                        groupOrder,
                        type,
                        size,
                        orderInGroup));
            } catch (Throwable throwable) {
                constructorFailures++;
                if (configs.isEmpty()) {
                    throw throwable;
                }
            }
        }

        return new SyntheticBuild(
                configs,
                "bridgeReady=true"
                        + ", providers=" + providers.size()
                        + ", existingConfigs=" + ((List<?>) existingConfigValue).size()
                        + ", existingSynthetic=" + existingSynthetic
                        + ", candidates=" + candidates.size()
                        + ", generated=" + configs.size()
                        + ", configuredActivities=" + configuredActivities
                        + ", iconPackages=" + existingIconPackages + "/"
                        + localIconPackages.size() + "/" + missingIconPackages.size()
                        + ", titlePackages=" + existingTitlePackages + "/"
                        + localTitlePackages.size() + "/" + missingTitlePackages.size()
                        + ", sizes=" + sizeOne + '/' + sizeTwo + '/' + sizeThree
                        + ", configuredSkipped=" + configuredSkipped
                        + ", disabledSkipped=" + disabledSkipped
                        + ", foreignProfileSkipped=" + foreignProfileSkipped
                        + ", nonHomeSkipped=" + nonHomeSkipped
                        + ", duplicateSkipped=" + duplicateSkipped
                        + ", malformedProvider=" + malformedProvider
                        + ", malformedConfig=" + malformedConfig
                        + ", typeCollisionSkipped=" + typeCollisionSkipped
                        + ", constructorFailures=" + constructorFailures
                        + ", bridge={" + bridgeSummary + "}");
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerRefreshReceiver() {
        if (!refreshReceiverRegistered.compareAndSet(false, true)) {
            return;
        }
        Context context = findApplicationContext();
        if (context == null) {
            refreshReceiverRegistered.set(false);
            return;
        }
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ignoredContext, Intent ignoredIntent) {
                refreshUmsRepository();
            }
        };
        try {
            IntentFilter filter = new IntentFilter(ModuleBridge.ACTION_REFRESH);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            refreshReceiver = receiver;
            info("UMS 卡片刷新广播已注册");
        } catch (Throwable throwable) {
            refreshReceiverRegistered.set(false);
            error("UMS 卡片刷新广播注册失败", throwable);
        }
    }

    private void refreshUmsRepository() {
        if (!refreshInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            disabledCardIdsReadAt = 0L;
            Object repository = configRepository.get().get();
            if (repository == null) {
                info("收到卡片刷新请求，但 UMS 配置仓库尚未就绪");
                return;
            }
            refreshProviderSnapshot(repository);
            Method rebuild = findBooleanMethod(repository, "d");
            if (rebuild == null) {
                info("收到卡片刷新请求，但未找到 UMS 重建入口");
                return;
            }
            rebuild.invoke(repository, true);
            preflight("UMS 主动卡片刷新完成");
        } catch (Throwable throwable) {
            error("UMS 主动卡片刷新失败；保持宿主原有行为", throwable);
        } finally {
            refreshInProgress.set(false);
        }
    }

    private void refreshProviderSnapshot(Object repository) {
        try {
            Object providerLazy = readField(repository, "d");
            Object providerRepository = invokeNoArg(providerLazy, "getValue");
            List<AppWidgetProviderInfo> current = snapshotProviderInfos(
                    invokeNoArg(providerRepository, "a"));
            if (!current.isEmpty()) {
                latestProviderInfos.set(current);
            }
        } catch (Throwable throwable) {
            error("主动刷新 Provider 快照失败；沿用最近一次快照", throwable);
        }
    }

    private static Method findBooleanMethod(Object owner, String methodName) {
        if (owner == null) {
            return null;
        }
        Class<?> type = owner.getClass();
        while (type != null && type != Object.class) {
            for (Method method : type.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (!methodName.equals(method.getName())
                        || parameters.length != 1
                        || (parameters[0] != boolean.class && parameters[0] != Boolean.class)) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    return method;
                } catch (Throwable ignored) {
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private void publishCatalogSnapshot(
            Object existingConfigValue,
            List<AppWidgetProviderInfo> providers,
            Set<String> configuredComponents) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastCatalogPublish.get() < 750L
                || !(existingConfigValue instanceof Iterable<?>)) {
            return;
        }
        lastCatalogPublish.set(now);
        try {
            JSONArray cards = new JSONArray();
            HashSet<String> ids = new HashSet<>();
            int index = 0;
            for (Object item : (Iterable<?>) existingConfigValue) {
                JSONObject card = catalogCardFromConfig(item, index++);
                if (card == null) {
                    continue;
                }
                String id = card.optString("id", "");
                if (!id.isEmpty() && ids.add(id)) {
                    cards.put(card);
                }
            }
            if (providers != null) {
                for (AppWidgetProviderInfo info : providers) {
                    if (!isEligibleProvider(info)) {
                        continue;
                    }
                    String componentKey = providerComponentKey(info);
                    if (configuredComponents != null
                            && configuredComponents.contains(componentKey)) {
                        continue;
                    }
                    JSONObject card = catalogCardFromProvider(info);
                    if (card == null) {
                        continue;
                    }
                    String id = card.optString("id", "");
                    if (!id.isEmpty() && ids.add(id)) {
                        cards.put(card);
                    }
                }
            }
            JSONObject catalog = new JSONObject();
            catalog.put("version", 1);
            catalog.put("updatedAt", System.currentTimeMillis());
            catalog.put("cards", cards);
            Context context = findApplicationContext();
            if (context == null) {
                return;
            }
            Bundle extras = new Bundle();
            extras.putString(ModuleBridge.KEY_CATALOG, catalog.toString());
            context.getContentResolver().call(
                    ModuleBridge.CONTROL_URI,
                    ModuleBridge.METHOD_PUBLISH_CATALOG,
                    null,
                    extras);
            preflight("第三方卡片目录发布：total=" + cards.length());
        } catch (Throwable throwable) {
            error("发布卡片目录失败；不影响宿主配置", throwable);
        }
    }

    private void publishRepositoryCatalog(Object repository) {
        try {
            Object cloudConfigs = readField(repository, "g");
            Object matchedConfigs = readField(repository, "h");
            Object catalogConfigs = matchCatalogConfigs(cloudConfigs, matchedConfigs);
            if (!(catalogConfigs instanceof Iterable<?>)) {
                return;
            }
            publishCatalogSnapshot(
                    catalogConfigs,
                    latestProviderInfos.get(),
                    configuredComponentsFrom(catalogConfigs));
        } catch (Throwable throwable) {
            error("读取 UMS 最终卡片目录失败；沿用已有目录", throwable);
        }
    }

    private static Object matchCatalogConfigs(Object cloudConfigs, Object matchedConfigs) {
        if (!(cloudConfigs instanceof Iterable<?>)) {
            return matchedConfigs;
        }
        if (!(matchedConfigs instanceof Iterable<?>)) {
            return cloudConfigs;
        }
        HashSet<String> matchedKeys = new HashSet<>();
        for (Object item : (Iterable<?>) matchedConfigs) {
            addCardIdentityKeys(matchedKeys, item);
        }
        if (matchedKeys.isEmpty()) {
            return cloudConfigs;
        }
        ArrayList<Object> filtered = new ArrayList<>();
        for (Object item : (Iterable<?>) cloudConfigs) {
            if (hasCardIdentityKey(item, matchedKeys)) {
                filtered.add(item);
            }
        }
        return filtered.isEmpty() ? cloudConfigs : filtered;
    }

    private static boolean hasCardIdentityKey(Object config, Set<String> expected) {
        HashSet<String> keys = new HashSet<>();
        addCardIdentityKeys(keys, config);
        for (String key : keys) {
            if (expected.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private static void addCardIdentityKeys(Set<String> target, Object config) {
        if (target == null || config == null) {
            return;
        }
        try {
            String extras = asString(invokeNoArg(config, "getExtras"));
            if (extras != null && !extras.isEmpty()) {
                try {
                    String component = new JSONObject(extras)
                            .optString(MARKER_COMPONENT_KEY, "");
                    if (!component.isEmpty()) {
                        target.add("component:" + component);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            String packageName = asString(invokeNoArg(config, "getPackageName"));
            String componentName = asString(invokeNoArg(config, "getComponentName"));
            if (packageName != null && !packageName.isEmpty()
                    && componentName != null && !componentName.isEmpty()) {
                target.add("component:" + providerComponentKey(packageName, componentName));
            }
        } catch (Throwable ignored) {
        }
        try {
            Integer type = asInteger(invokeNoArg(config, "getType"));
            if (type != null) {
                target.add("type:" + type);
            }
        } catch (Throwable ignored) {
        }
    }

    private static Set<String> configuredComponentsFrom(Object value) {
        HashSet<String> components = new HashSet<>();
        if (!(value instanceof Iterable<?>)) {
            return components;
        }
        for (Object item : (Iterable<?>) value) {
            try {
                String packageName = asString(invokeNoArg(item, "getPackageName"));
                String componentName = asString(invokeNoArg(item, "getComponentName"));
                if (packageName != null && !packageName.isEmpty()
                        && componentName != null && !componentName.isEmpty()) {
                    components.add(providerComponentKey(packageName, componentName));
                }
            } catch (Throwable ignored) {
            }
        }
        return components;
    }

    private JSONObject catalogCardFromConfig(Object item, int fallbackIndex) {
        try {
            Integer type = asInteger(invokeNoArg(item, "getType"));
            String packageName = asString(invokeNoArg(item, "getPackageName"));
            String componentName = asString(invokeNoArg(item, "getComponentName"));
            String groupTitle = asString(invokeNoArg(item, "getGroupTitle"));
            String groupIcon = asString(invokeNoArg(item, "getGroupIcon"));
            String extras = "";
            try {
                String value = asString(invokeNoArg(item, "getExtras"));
                if (value != null) {
                    extras = value;
                }
            } catch (Throwable ignored) {
            }
            boolean synthetic = isSyntheticConfig(item);
            if (!synthetic) {
                return null;
            }
            String markedComponent = "";
            if (extras != null && !extras.isEmpty()) {
                try {
                    markedComponent = new JSONObject(extras)
                            .optString(MARKER_COMPONENT_KEY, "");
                } catch (Throwable ignored) {
                }
            }
            String id = !markedComponent.isEmpty()
                    ? markedComponent
                    : packageName != null && !packageName.isEmpty()
                    && componentName != null && !componentName.isEmpty()
                    ? providerComponentKey(packageName, componentName)
                    : type == null
                    ? "third-party:" + fallbackIndex
                    : "type:" + type;
            String title = firstNonEmptyString(
                    item,
                    "getWidgetName",
                    "getCardName",
                    "getName",
                    "getTitle");
            if (title.isEmpty()) {
                title = componentTail(componentName, type == null ? "卡片" : "卡片 " + type);
            }
            String appLabel = groupTitle == null ? "" : groupTitle.trim();
            if (appLabel.isEmpty()) {
                appLabel = resolvePackageLabel(packageName);
            }
            if (appLabel.isEmpty()) {
                appLabel = packageName == null || packageName.isEmpty()
                        ? "第三方应用"
                        : packageName;
            }
            String icon = groupIcon == null ? "" : groupIcon.trim();
            if (icon.isEmpty() && packageName != null && !packageName.isEmpty()) {
                icon = iconUriForPackage(packageName);
            }
            JSONObject result = new JSONObject();
            result.put("id", id);
            result.put("packageName", packageName == null ? "" : packageName);
            result.put("componentName", componentName == null ? "" : componentName);
            result.put("appLabel", appLabel);
            result.put("title", title);
            result.put("subtitle", componentTail(componentName, "桌面小组件"));
            result.put("icon", icon);
            result.put("synthetic", true);
            if (type != null) {
                result.put("type", type);
            }
            return result;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private JSONObject catalogCardFromProvider(AppWidgetProviderInfo info) {
        if (info == null || info.provider == null) {
            return null;
        }
        try {
            ComponentName provider = info.provider;
            String packageName = provider.getPackageName();
            String componentName = provider.getClassName();
            String title = "";
            Context context = findApplicationContext();
            if (context != null) {
                try {
                    CharSequence label = info.loadLabel(context.getPackageManager());
                    title = label == null ? "" : label.toString().trim();
                } catch (Throwable ignored) {
                }
            }
            if (title.isEmpty()) {
                title = componentTail(componentName, "桌面小组件");
            }
            String appLabel = resolvePackageLabel(packageName);
            JSONObject result = new JSONObject();
            result.put("id", providerComponentKey(info));
            result.put("packageName", packageName);
            result.put("componentName", componentName);
            result.put("appLabel", appLabel.isEmpty() ? packageName : appLabel);
            result.put("title", title);
            result.put("subtitle", componentTail(componentName, "桌面小组件"));
            result.put("icon", iconUriForPackage(packageName));
            result.put("synthetic", true);
            result.put("size", classifyWidgetSize(info));
            return result;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String resolvePackageLabel(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return "";
        }
        Context context = findApplicationContext();
        if (context == null) {
            return "";
        }
        try {
            android.content.pm.ApplicationInfo info = context.getPackageManager()
                    .getApplicationInfo(packageName, 0);
            CharSequence label = context.getPackageManager().getApplicationLabel(info);
            return label == null ? "" : label.toString().trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String firstNonEmptyString(Object owner, String... methodNames) {
        for (String methodName : methodNames) {
            try {
                String value = asString(invokeNoArg(owner, methodName));
                if (value != null && !value.trim().isEmpty()) {
                    return value.trim();
                }
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    private static String componentTail(String componentName, String fallback) {
        if (componentName == null || componentName.isEmpty()) {
            return fallback;
        }
        int separator = componentName.lastIndexOf('.');
        String tail = separator >= 0 && separator + 1 < componentName.length()
                ? componentName.substring(separator + 1)
                : componentName;
        return tail.isEmpty() ? fallback : tail;
    }

    private static String iconUriForPackage(String packageName) {
        return new Uri.Builder()
                .scheme("content")
                .authority(ICON_PROVIDER_AUTHORITY)
                .appendPath(ICON_PROVIDER_APP_PATH)
                .appendPath(packageName)
                .build()
                .toString();
    }

    private static boolean isEligibleProvider(AppWidgetProviderInfo info) {
        if (info == null || info.provider == null) {
            return false;
        }
        try {
            UserHandle profile = info.getProfile();
            return (profile == null || Process.myUserHandle().equals(profile))
                    && (info.widgetCategory == 0
                    || (info.widgetCategory
                    & AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) != 0);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private List<AppWidgetProviderInfo> resolveProviderInfos(
            Object repository,
            List<AppWidgetProviderInfo> fallback) {
        try {
            Object providerLazy = readField(repository, "d");
            Object providerRepository = invokeNoArg(providerLazy, "getValue");
            List<AppWidgetProviderInfo> current = snapshotProviderInfos(
                    invokeNoArg(providerRepository, "a"));
            if (!current.isEmpty()) {
                latestProviderInfos.set(current);
                return current;
            }
        } catch (Throwable ignored) {
            // 使用最后一次成功快照；为空时由调用方 fail-closed。
        }
        return fallback == null ? Collections.emptyList() : fallback;
    }

    private static List<AppWidgetProviderInfo> snapshotProviderInfos(Object value) {
        if (!(value instanceof Iterable<?>)) {
            return Collections.emptyList();
        }
        ArrayList<AppWidgetProviderInfo> snapshot = new ArrayList<>();
        for (Object item : (Iterable<?>) value) {
            if (item instanceof AppWidgetProviderInfo) {
                snapshot.add((AppWidgetProviderInfo) item);
            }
        }
        return Collections.unmodifiableList(snapshot);
    }

    private Bundle queryResidentBridgeOnce() {
        Context context = findApplicationContext();
        if (context == null) {
            return null;
        }
        Bundle request = new Bundle();
        request.putInt(PARAM_CARD_TYPE, SYNTHETIC_TYPE);
        request.putInt(PARAM_ADD_CARD_TO, ADD_CARD_TO_ASSISTANT_SCREEN);
        try {
            return context.getContentResolver().call(
                    ASSISTANT_PROVIDER_URI,
                    METHOD_QUERY_ADD_CARD_STATE,
                    null,
                    request);
        } catch (Throwable throwable) {
            error("UMS resident bridge 同步查询失败；本次注入 fail-closed", throwable);
            return null;
        }
    }

    private static boolean isResidentBridgeReady(Bundle bridge) {
        return bridge != null
                && bridge.getInt(BRIDGE_VERSION_KEY, -1) == RESIDENT_BRIDGE_VERSION
                && bridge.getBoolean(BRIDGE_KNOWN_KEY, false)
                && bridge.getBoolean(BRIDGE_READY_KEY, false)
                && bridge.getInt(BRIDGE_CONTROL_TYPE_KEY, -1) == 0
                && bridge.getInt(BRIDGE_MALFORMED_KEY, -1) == 0;
    }

    private Object createOperationWidgetConfig(
            ClassLoader classLoader,
            AppWidgetProviderInfo info,
            int groupId,
            String groupTitle,
            String groupIcon,
            int groupOrder,
            int type,
            int size,
            int orderInGroup) throws Exception {
        Constructor<?> constructor = operationWidgetConfigConstructor;
        if (constructor == null) {
            synchronized (this) {
                constructor = operationWidgetConfigConstructor;
                if (constructor == null) {
                    Class<?> configClass = Class.forName(
                            "com.oplus.ums.card.configuration.widget.data."
                                    + "OperationWidgetConfigPO",
                            false,
                            classLoader);
                    for (Constructor<?> candidate : configClass.getDeclaredConstructors()) {
                        Class<?>[] parameters = candidate.getParameterTypes();
                        if (parameters.length == 36
                                && parameters[0] == int.class
                                && parameters[1] == String.class
                                && parameters[14] == boolean.class
                                && parameters[35].getName().endsWith("CardMaintainVo")) {
                            candidate.setAccessible(true);
                            constructor = candidate;
                            operationWidgetConfigConstructor = candidate;
                            break;
                        }
                    }
                    if (constructor == null) {
                        throw new NoSuchMethodException(
                                "OperationWidgetConfigPO primary constructor");
                    }
                }
            }
        }
        ComponentName provider = info.provider;
        JSONObject extras = new JSONObject();
        extras.put(MARKER_KEY, MARKER_VALUE);
        extras.put(MARKER_COMPONENT_KEY, providerComponentKey(info));
        return constructor.newInstance(
                groupId,
                groupTitle,
                groupIcon,
                groupOrder,
                type,
                "",
                "",
                "",
                "",
                size,
                orderInGroup,
                provider.getPackageName(),
                provider.getClassName(),
                OPERATION_WIDGET_CATEGORY,
                info.resizeMode != AppWidgetProviderInfo.RESIZE_NONE,
                1,
                0,
                Math.max(0, info.minWidth),
                Math.max(0, info.minHeight),
                "",
                "",
                0,
                0,
                1,
                "",
                "",
                "",
                "",
                "",
                1,
                extras.toString(),
                0,
                "",
                "",
                SYNTHETIC_PRIORITY,
                null);
    }

    private Object convertOperationWidgetToRaw(
            ClassLoader classLoader, Object operationWidgetConfig) throws Exception {
        Method converter = operationWidgetToRawMethod;
        if (converter == null) {
            synchronized (this) {
                converter = operationWidgetToRawMethod;
                if (converter == null) {
                    Class<?> owner = Class.forName(
                            "com.oplus.ums.card.configuration.data.convert.i",
                            false,
                            classLoader);
                    converter = owner.getDeclaredMethod(
                            "b", operationWidgetConfig.getClass());
                    converter.setAccessible(true);
                    operationWidgetToRawMethod = converter;
                }
            }
        }
        return converter.invoke(null, operationWidgetConfig);
    }

    private String resolveLocalGroupTitle(AppWidgetProviderInfo info) {
        if (info == null || info.provider == null) {
            return "";
        }
        String packageName = info.provider.getPackageName();
        Context context = findApplicationContext();
        if (context != null) {
            try {
                android.content.pm.PackageManager packageManager = context.getPackageManager();
                CharSequence label = packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(packageName, 0));
                if (label != null) {
                    String title = label.toString().trim();
                    if (!title.isEmpty()) {
                        return title;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return packageName;
    }

    private static String resolveLocalGroupIcon(AppWidgetProviderInfo info) {
        if (info == null || info.provider == null) {
            return "";
        }
        return new Uri.Builder()
                .scheme("content")
                .authority(ICON_PROVIDER_AUTHORITY)
                .appendPath(ICON_PROVIDER_APP_PATH)
                .appendPath(info.provider.getPackageName())
                .build()
                .toString();
    }

    private static int classifyWidgetSize(AppWidgetProviderInfo info) {
        int targetWidth = 0;
        int targetHeight = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            targetWidth = info.targetCellWidth;
            targetHeight = info.targetCellHeight;
        }
        if (targetWidth > 0 && targetHeight > 0) {
            if (targetHeight >= 4 || (targetWidth >= 4 && targetHeight >= 3)) {
                return 3;
            }
            if (targetWidth >= 4 || targetWidth >= targetHeight * 2) {
                return 2;
            }
            return 1;
        }
        int width = Math.max(0, info.minWidth);
        int height = Math.max(0, info.minHeight);
        if (height > 0 && width >= height * 3 / 2) {
            return 2;
        }
        if (width > 0 && height >= width * 5 / 4) {
            return 3;
        }
        if (width >= 700 && height >= 550) {
            return 3;
        }
        return 1;
    }

    private static int allocateStableId(
            String key,
            int prefix,
            int mask,
            Set<Integer> usedValues) {
        for (int salt = 0; salt < 32; salt++) {
            String salted = salt == 0 ? key : key + '#' + salt;
            int candidate = prefix | (stableHash(salted) & mask);
            if (candidate != SYNTHETIC_TYPE && usedValues.add(candidate)) {
                return candidate;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static int stableHash(String value) {
        int hash = 0x811c9dc5;
        for (int index = 0; index < value.length(); index++) {
            hash ^= value.charAt(index);
            hash *= 0x01000193;
        }
        return hash;
    }

    private static boolean isSyntheticConfig(Object config) {
        try {
            String extras = asString(invokeNoArg(config, "getExtras"));
            return extras != null
                    && MARKER_VALUE.equals(new JSONObject(extras).optString(MARKER_KEY, null));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String providerComponentKey(AppWidgetProviderInfo info) {
        if (info == null || info.provider == null) {
            return "";
        }
        return providerComponentKey(
                info.provider.getPackageName(), info.provider.getClassName());
    }

    private static String providerComponentKey(String packageName, String componentName) {
        return packageName + '/' + componentName;
    }

    private static final class SyntheticTrigger {
        final Object augmentedInput;
        final String summary;

        SyntheticTrigger(Object augmentedInput, String summary) {
            this.augmentedInput = augmentedInput;
            this.summary = summary;
        }

        static SyntheticTrigger none(String summary) {
            return new SyntheticTrigger(null, summary);
        }
    }

    private static final class SyntheticInjection {
        final List<Object> added;
        final String summary;

        SyntheticInjection(List<Object> added, String summary) {
            this.added = added;
            this.summary = summary;
        }

        static SyntheticInjection none(String summary) {
            return new SyntheticInjection(Collections.emptyList(), summary);
        }
    }

    private static final class SyntheticBuild {
        final List<Object> configs;
        final String summary;

        SyntheticBuild(List<Object> configs, String summary) {
            this.configs = configs;
            this.summary = summary;
        }

        static SyntheticBuild empty(String summary) {
            return new SyntheticBuild(Collections.emptyList(), summary);
        }
    }

    private static final class DisabledConfigRemoval {
        final List<RemovedConfig> removed;

        DisabledConfigRemoval(List<RemovedConfig> removed) {
            this.removed = removed;
        }

        static DisabledConfigRemoval none() {
            return new DisabledConfigRemoval(Collections.emptyList());
        }
    }

    private static final class RemovedConfig {
        final int index;
        final Object value;

        RemovedConfig(int index, Object value) {
            this.index = index;
            this.value = value;
        }
    }

    private Context findApplicationContext() {
        Object application = invokeStaticNoArgSafely(
                "android.app.ActivityThread", "currentApplication");
        if (!(application instanceof Context)) {
            application = invokeStaticNoArgSafely(
                    "android.app.AppGlobals", "getInitialApplication");
        }
        if (!(application instanceof Context)) {
            return null;
        }
        Context context = (Context) application;
        Context applicationContext = context.getApplicationContext();
        return applicationContext != null ? applicationContext : context;
    }

    private static int sizeOf(Object value) {
        if (value instanceof List<?>) {
            return ((List<?>) value).size();
        }
        if (value != null && value.getClass().isArray()) {
            return java.lang.reflect.Array.getLength(value);
        }
        return -1;
    }

    /** 调用下一层并原样返回；异常也原样重新抛出。 */
    private Object proceedUnchanged(
            XposedInterface.Chain chain, String stage, boolean logFailure) throws Throwable {
        try {
            return chain.proceed();
        } catch (Throwable throwable) {
            if (logFailure) {
                try {
                    error(stage + ": 原方法抛出异常（将原样继续抛出）", throwable);
                } catch (Throwable ignored) {
                    // 日志失败也必须保留宿主原异常。
                }
            }
            throw throwable;
        }
    }

    private Object proceedWithArgs(
            XposedInterface.Chain chain,
            String stage,
            Object[] args,
            boolean logFailure) throws Throwable {
        try {
            return chain.proceed(args);
        } catch (Throwable throwable) {
            if (logFailure) {
                try {
                    error(stage + ": 原方法抛出异常（将原样继续抛出）", throwable);
                } catch (Throwable ignored) {
                    // 日志失败也必须保留宿主原异常。
                }
            }
            throw throwable;
        }
    }

    private void hookMethod(
            ClassLoader classLoader,
            String className,
            String methodName,
            Class<?>[] parameterTypes,
            XposedInterface.Hooker hooker) {
        Class<?> owner = findClass(classLoader, className);
        if (owner == null) {
            return;
        }
        try {
            Method method = owner.getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            hook(method).setId("cge-" + className + "-" + methodName).intercept(hooker);
            info("Hook 已安装：" + signature(method));
        } catch (Throwable throwable) {
            error("Hook 安装失败：" + className + "#" + methodName, throwable);
        }
    }

    private Class<?> findClass(ClassLoader classLoader, String className) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (Throwable throwable) {
            error("找不到目标类：" + className, throwable);
            return null;
        }
    }

    private String summarizeResident(Object service) {
        ResidentSnapshot snapshot = readResidentSnapshot(service);
        if (!snapshot.known) {
            return "ready=unknown, size=unknown, controlType=unknown, structureMismatch=true";
        }
        ResidentState state = snapshot.state;
        return "ready=" + snapshot.ready
                + ", size=" + state.size
                + ", recognized=" + state.recognized
                + ", controlType=" + state.controlType
                + ", nullProvider=" + state.nullProvider
                + ", componentProvider=" + state.componentProvider
                + ", malformed=" + state.malformed
                + ", layout=" + snapshot.layoutSummary;
    }

    private String summarizeRawConfigs(Object value) {
        if (!(value instanceof List<?>)) {
            return "listType=" + classNameOf(value) + ", state=unknown";
        }
        List<?> list = (List<?>) value;
        int controlType = 0;
        int markerMatches = 0;
        int componentMatches = 0;
        int markerParseErrors = 0;
        int malformed = 0;
        for (Object item : list) {
            try {
                Integer type = asInteger(invokeNoArg(item, "getType"));
                String packageName = asString(invokeNoArg(item, "getPackageName"));
                String componentName = asString(invokeNoArg(item, "getComponentName"));
                String extras = asString(invokeNoArg(item, "getExtras"));
                if (type != null && type == SYNTHETIC_TYPE) {
                    controlType++;
                }
                if (extras == null || !extras.contains(MARKER_KEY)) {
                    continue;
                }
                try {
                    JSONObject marker = new JSONObject(extras);
                    if (!MARKER_VALUE.equals(marker.optString(MARKER_KEY, null))) {
                        continue;
                    }
                    markerMatches++;
                    String markedComponent = marker.optString(MARKER_COMPONENT_KEY, "");
                    if (packageName != null
                            && componentName != null
                            && markedComponent.equals(
                            providerComponentKey(packageName, componentName))) {
                        componentMatches++;
                    }
                } catch (Throwable ignored) {
                    markerParseErrors++;
                }
            } catch (Throwable ignored) {
                malformed++;
            }
        }
        return "size=" + list.size()
                + ", controlType=" + controlType
                + ", markerMatches=" + markerMatches
                + ", componentMatches=" + componentMatches
                + ", markerParseErrors=" + markerParseErrors
                + ", malformed=" + malformed;
    }

    private String summarizeUmsRepository(Object repository) {
        if (repository == null) {
            return "state=unknown, repository=null";
        }
        try {
            Object cloudConfigs = readField(repository, "g");
            Object matchedConfigs = readField(repository, "h");
            Object emptyPublished = readField(repository, "j");
            return "cloud=" + summarizeRawConfigs(cloudConfigs)
                    + ", matchedSize=" + sizeOf(matchedConfigs)
                    + ", emptyPublished=" + safeText(emptyPublished, 80);
        } catch (Throwable ignored) {
            return "state=unknown, structureMismatch=true";
        }
    }

    private ResidentSnapshot readResidentSnapshot(Object service) {
        if (service == null) {
            return ResidentSnapshot.unknown();
        }
        ResidentLayout layout = residentLayout;
        if (layout == null || !layout.matches(service)) {
            layout = discoverResidentLayout(service);
            if (layout == null) {
                return ResidentSnapshot.unknown();
            }
            residentLayout = layout;
        }
        try {
            Object value = layout.residentField.get(service);
            if (!(value instanceof Iterable<?>)) {
                return ResidentSnapshot.unknown();
            }
            ResidentState state = inspectResidentState((Iterable<?>) value);
            Boolean readyValue = layout.readReady(service);
            boolean ready = readyValue != null
                    ? readyValue
                    : state.recognized > 0 && state.malformed == 0;
            return new ResidentSnapshot(true, ready, state, layout.summary());
        } catch (Throwable ignored) {
            return ResidentSnapshot.unknown();
        }
    }

    private static ResidentLayout discoverResidentLayout(Object service) {
        List<Field> fields = instanceFields(service.getClass());
        ResidentFieldCandidate best = null;
        for (int index = 0; index < fields.size(); index++) {
            Field field = fields.get(index);
            if (!Iterable.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                Object value = field.get(service);
                if (!(value instanceof Iterable<?>)) {
                    continue;
                }
                ResidentState state = inspectResidentState((Iterable<?>) value);
                int score = state.recognized * 1000
                        - state.malformed * 100
                        + Math.min(state.size, 99);
                if (String.valueOf(field.getGenericType()).contains("CardDisplay")) {
                    score += 250;
                }
                if (best == null || score > best.score) {
                    best = new ResidentFieldCandidate(field, index, state, score);
                }
            } catch (Throwable ignored) {
            }
        }
        if (best == null || best.state.recognized == 0) {
            return null;
        }
        Field readyField = chooseReadyField(fields, best.fieldIndex);
        return new ResidentLayout(service.getClass(), best.field, readyField);
    }

    private static Field chooseReadyField(List<Field> fields, int residentFieldIndex) {
        Field nearestAfter = null;
        Field nearest = null;
        int nearestDistance = Integer.MAX_VALUE;
        for (int index = 0; index < fields.size(); index++) {
            Field field = fields.get(index);
            Class<?> fieldType = field.getType();
            if (fieldType != boolean.class && fieldType != Boolean.class) {
                continue;
            }
            int distance = Math.abs(index - residentFieldIndex);
            if (distance < nearestDistance) {
                nearest = field;
                nearestDistance = distance;
            }
            if (index > residentFieldIndex && nearestAfter == null) {
                nearestAfter = field;
            }
        }
        return nearestAfter != null ? nearestAfter : nearest;
    }

    private static List<Field> instanceFields(Class<?> owner) {
        ArrayList<Field> fields = new ArrayList<>();
        Class<?> type = owner;
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    fields.add(field);
                } catch (Throwable ignored) {
                }
            }
            type = type.getSuperclass();
        }
        return fields;
    }

    private static boolean isResidentBridgeRequest(Object value) {
        if (!(value instanceof Bundle)) {
            return false;
        }
        Bundle bundle = (Bundle) value;
        return bundle.getInt(PARAM_CARD_TYPE, Integer.MIN_VALUE) == SYNTHETIC_TYPE
                && bundle.getInt(PARAM_ADD_CARD_TO, Integer.MIN_VALUE)
                == ADD_CARD_TO_ASSISTANT_SCREEN;
    }

    private static boolean isPackageForUid(Object provider, int uid, String packageName) {
        if (!(provider instanceof ContentProvider)) {
            return false;
        }
        try {
            Context context = ((ContentProvider) provider).getContext();
            if (context == null) {
                return false;
            }
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            if (packages == null) {
                return false;
            }
            for (String candidate : packages) {
                if (packageName.equals(candidate)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 调用方识别失败时保持原返回，绝不开放桥接信息。
        }
        return false;
    }

    private static ResidentState inspectResidentState(Iterable<?> residents) {
        ResidentState state = new ResidentState();
        if (residents instanceof Collection<?>) {
            state.size = ((Collection<?>) residents).size();
        }
        int inspected = 0;
        try {
            for (Object resident : residents) {
                if (inspected >= MAX_RESIDENT_SCAN) {
                    state.malformed++;
                    break;
                }
                inspected++;
                if (!(residents instanceof Collection<?>)) {
                    state.size++;
                }
                DisplayInfoSnapshot displayInfo = findDisplayInfo(resident);
                if (displayInfo == null || displayInfo.type == null) {
                    state.malformed++;
                    continue;
                }
                state.recognized++;
                if (displayInfo.type != SYNTHETIC_TYPE) {
                    continue;
                }
                state.controlType++;
                if (!displayInfo.providerKnown) {
                    state.malformed++;
                    continue;
                }
                if (displayInfo.provider == null) {
                    state.nullProvider++;
                } else {
                    state.componentProvider++;
                }
            }
        } catch (Throwable ignored) {
            state.malformed++;
        }
        return state;
    }

    private static DisplayInfoSnapshot findDisplayInfo(Object resident) {
        if (resident == null) {
            return null;
        }
        DisplayInfoSnapshot direct = inspectDisplayInfo(resident);
        if (direct != null) {
            return direct;
        }
        DisplayInfoSnapshot best = null;
        for (Field field : instanceFields(resident.getClass())) {
            Class<?> fieldType = field.getType();
            if (fieldType.isPrimitive()
                    || fieldType.isEnum()
                    || fieldType == String.class
                    || Number.class.isAssignableFrom(fieldType)
                    || fieldType == Boolean.class
                    || fieldType == Character.class) {
                continue;
            }
            try {
                Object candidate = field.get(resident);
                if (candidate == null) {
                    continue;
                }
                DisplayInfoSnapshot inspected = inspectDisplayInfo(candidate);
                if (inspected != null
                        && (best == null || inspected.confidence > best.confidence)) {
                    best = inspected;
                }
            } catch (Throwable ignored) {
            }
        }
        return best;
    }

    private static DisplayInfoSnapshot inspectDisplayInfo(Object candidate) {
        Class<?> type = candidate.getClass();
        int confidence = displayInfoConfidence(type);
        if (confidence == 0) {
            return null;
        }

        Integer cardType = null;
        Method typeGetter = findNoArgMethod(type, "getType");
        if (typeGetter != null) {
            try {
                Object value = typeGetter.invoke(candidate);
                if (value instanceof Integer) {
                    cardType = (Integer) value;
                }
            } catch (Throwable ignored) {
            }
        }
        if (cardType == null) {
            cardType = findSyntheticTypeValue(candidate);
        }
        if (cardType == null) {
            cardType = findTypeValueFromText(candidate);
        }

        boolean providerKnown = false;
        ComponentName provider = null;
        Method providerGetter = findNoArgMethod(type, "getWidgetProvider");
        if (providerGetter == null) {
            providerGetter = findComponentNameGetter(type);
        }
        if (providerGetter != null) {
            try {
                Object value = providerGetter.invoke(candidate);
                if (value == null || value instanceof ComponentName) {
                    providerKnown = true;
                    provider = (ComponentName) value;
                }
            } catch (Throwable ignored) {
            }
        }
        if (!providerKnown) {
            for (Field field : instanceFields(type)) {
                if (!ComponentName.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    Object value = field.get(candidate);
                    if (value == null || value instanceof ComponentName) {
                        providerKnown = true;
                        provider = (ComponentName) value;
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return cardType != null || providerKnown
                ? new DisplayInfoSnapshot(cardType, provider, providerKnown, confidence)
                : null;
    }

    private static int displayInfoConfidence(Class<?> type) {
        boolean namedCardDisplayInfo = type.getName().endsWith(".CardDisplayInfo");
        int confidence = namedCardDisplayInfo ? 100 : 0;
        Method typeGetter = findNoArgMethod(type, "getType");
        boolean hasTypeGetter = typeGetter != null
                && (typeGetter.getReturnType() == int.class
                || typeGetter.getReturnType() == Integer.class);
        if (hasTypeGetter) {
            confidence += 40;
        }
        Method providerGetter = findNoArgMethod(type, "getWidgetProvider");
        boolean hasProviderShape = false;
        if (providerGetter != null
                && ComponentName.class.isAssignableFrom(providerGetter.getReturnType())) {
            confidence += 40;
            hasProviderShape = true;
        } else if (findComponentNameGetter(type) != null) {
            confidence += 20;
            hasProviderShape = true;
        } else if (hasComponentNameField(type)) {
            confidence += 20;
            hasProviderShape = true;
        }
        int integerGetterCount = 0;
        for (Method method : type.getMethods()) {
            Class<?> returnType = method.getReturnType();
            if (method.getParameterTypes().length == 0
                    && (returnType == int.class || returnType == Integer.class)) {
                integerGetterCount++;
            }
        }
        if (integerGetterCount >= 4) {
            confidence += 10;
        }
        return namedCardDisplayInfo || (hasTypeGetter && hasProviderShape) ? confidence : 0;
    }

    private static boolean hasComponentNameField(Class<?> owner) {
        for (Field field : instanceFields(owner)) {
            if (ComponentName.class.isAssignableFrom(field.getType())) {
                return true;
            }
        }
        return false;
    }

    private static Integer findSyntheticTypeValue(Object candidate) {
        for (Field field : instanceFields(candidate.getClass())) {
            Class<?> fieldType = field.getType();
            if (fieldType != int.class && fieldType != Integer.class) {
                continue;
            }
            try {
                Object value = field.get(candidate);
                if (value instanceof Integer && ((Integer) value) == SYNTHETIC_TYPE) {
                    return (Integer) value;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Integer findTypeValueFromText(Object candidate) {
        try {
            String text = String.valueOf(candidate);
            int marker = text.indexOf("type=");
            if (marker < 0) {
                return null;
            }
            int start = marker + "type=".length();
            int end = start;
            while (end < text.length()) {
                char character = text.charAt(end);
                if (character == ',' || character == ')' || Character.isWhitespace(character)) {
                    break;
                }
                end++;
            }
            return Integer.valueOf(text.substring(start, end));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findNoArgMethod(Class<?> owner, String methodName) {
        try {
            Method method = owner.getMethod(methodName);
            if (method.getParameterTypes().length != 0) {
                return null;
            }
            method.setAccessible(true);
            return method;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findComponentNameGetter(Class<?> owner) {
        for (Method method : owner.getMethods()) {
            if (method.getParameterTypes().length == 0
                    && ComponentName.class.isAssignableFrom(method.getReturnType())) {
                try {
                    method.setAccessible(true);
                    return method;
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static final class ResidentState {
        int size;
        int recognized;
        int controlType;
        int nullProvider;
        int componentProvider;
        int malformed;
    }

    private static final class ResidentSnapshot {
        final boolean known;
        final boolean ready;
        final ResidentState state;
        final String layoutSummary;

        ResidentSnapshot(
                boolean known,
                boolean ready,
                ResidentState state,
                String layoutSummary) {
            this.known = known;
            this.ready = ready;
            this.state = state;
            this.layoutSummary = layoutSummary;
        }

        static ResidentSnapshot unknown() {
            return new ResidentSnapshot(false, false, new ResidentState(), "unknown");
        }
    }

    private static final class ResidentFieldCandidate {
        final Field field;
        final int fieldIndex;
        final ResidentState state;
        final int score;

        ResidentFieldCandidate(Field field, int fieldIndex, ResidentState state, int score) {
            this.field = field;
            this.fieldIndex = fieldIndex;
            this.state = state;
            this.score = score;
        }
    }

    private static final class ResidentLayout {
        final Class<?> serviceClass;
        final Field residentField;
        final Field readyField;

        ResidentLayout(Class<?> serviceClass, Field residentField, Field readyField) {
            this.serviceClass = serviceClass;
            this.residentField = residentField;
            this.readyField = readyField;
        }

        boolean matches(Object service) {
            return service != null && serviceClass == service.getClass();
        }

        Boolean readReady(Object service) {
            if (readyField == null) {
                return null;
            }
            try {
                Object value = readyField.get(service);
                return value instanceof Boolean ? (Boolean) value : null;
            } catch (Throwable ignored) {
                return null;
            }
        }

        String summary() {
            return "service=" + serviceClass.getName()
                    + ", residents=" + residentField.getName()
                    + ", ready=" + (readyField == null ? "inferred" : readyField.getName());
        }
    }

    private static final class DisplayInfoSnapshot {
        final Integer type;
        final ComponentName provider;
        final boolean providerKnown;
        final int confidence;

        DisplayInfoSnapshot(
                Integer type,
                ComponentName provider,
                boolean providerKnown,
                int confidence) {
            this.type = type;
            this.provider = provider;
            this.providerKnown = providerKnown;
            this.confidence = confidence;
        }
    }

    private Object addResidentBridgeState(Object original, Object service) {
        Bundle response = original instanceof Bundle
                ? new Bundle((Bundle) original)
                : new Bundle();
        response.putInt(BRIDGE_VERSION_KEY, RESIDENT_BRIDGE_VERSION);
        response.putLong(BRIDGE_ELAPSED_REALTIME_KEY, SystemClock.elapsedRealtime());
        if (service == null) {
            response.putBoolean(BRIDGE_KNOWN_KEY, false);
            return response;
        }
        ResidentSnapshot snapshot = readResidentSnapshot(service);
        if (!snapshot.known) {
            response.putBoolean(BRIDGE_KNOWN_KEY, false);
            return response;
        }
        ResidentState state = snapshot.state;
        response.putBoolean(BRIDGE_KNOWN_KEY, true);
        response.putBoolean(BRIDGE_READY_KEY, snapshot.ready);
        response.putInt(BRIDGE_SIZE_KEY, state.size);
        response.putInt(BRIDGE_CONTROL_TYPE_KEY, state.controlType);
        response.putInt(BRIDGE_NULL_PROVIDER_KEY, state.nullProvider);
        response.putInt(BRIDGE_COMPONENT_PROVIDER_KEY, state.componentProvider);
        response.putInt(BRIDGE_MALFORMED_KEY, state.malformed);
        return response;
    }

    private static String summarizeResidentBridgeRequest(Object value) {
        if (!(value instanceof Bundle)) {
            return "type=" + classNameOf(value) + ", state=unknown";
        }
        Bundle bundle = (Bundle) value;
        return "cardType=" + bundle.getInt(PARAM_CARD_TYPE, Integer.MIN_VALUE)
                + ", addCardTo="
                + bundle.getInt(PARAM_ADD_CARD_TO, Integer.MIN_VALUE);
    }

    private static String summarizeResidentBridge(Object value) {
        if (!(value instanceof Bundle)) {
            return "type=" + classNameOf(value) + ", state=unknown";
        }
        Bundle bundle = (Bundle) value;
        try {
            return "version=" + safeText(bundle.get(BRIDGE_VERSION_KEY), 80)
                    + ", known=" + safeText(bundle.get(BRIDGE_KNOWN_KEY), 80)
                    + ", ready=" + safeText(bundle.get(BRIDGE_READY_KEY), 80)
                    + ", size=" + safeText(bundle.get(BRIDGE_SIZE_KEY), 80)
                    + ", controlType="
                    + safeText(bundle.get(BRIDGE_CONTROL_TYPE_KEY), 80)
                    + ", nullProvider="
                    + safeText(bundle.get(BRIDGE_NULL_PROVIDER_KEY), 80)
                    + ", componentProvider="
                    + safeText(bundle.get(BRIDGE_COMPONENT_PROVIDER_KEY), 80)
                    + ", malformed="
                    + safeText(bundle.get(BRIDGE_MALFORMED_KEY), 80)
                    + ", elapsedRealtime="
                    + safeText(bundle.get(BRIDGE_ELAPSED_REALTIME_KEY), 80);
        } catch (Throwable ignored) {
            return "state=unknown, readError=true";
        }
    }

    private void observeSafely(String stage, Runnable observation) {
        try {
            observation.run();
        } catch (Throwable throwable) {
            try {
                error("preflight " + stage + ": 摘要失败，宿主结果保持不变", throwable);
            } catch (Throwable ignored) {
                // 连日志本身失败也不能影响宿主。
            }
        }
    }

    private void preflight(String message) {
        long sequence = preflightSequence.incrementAndGet();
        Thread thread = Thread.currentThread();
        info("preflight#" + sequence
                + " t=" + SystemClock.elapsedRealtime()
                + " thread=" + thread.getName() + '/' + thread.getId()
                + " " + message);
    }

    private static Object readField(Object owner, String fieldName) throws Exception {
        if (owner == null) {
            throw new IllegalArgumentException("owner is null");
        }
        Class<?> type = owner.getClass();
        while (type != null && type != Object.class) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static Object invokeNoArg(Object owner, String methodName) throws Exception {
        if (owner == null) {
            throw new IllegalArgumentException("owner is null");
        }
        Class<?> type = owner.getClass();
        while (type != null && type != Object.class) {
            try {
                Method method = type.getDeclaredMethod(methodName);
                method.setAccessible(true);
                return method.invoke(owner);
            } catch (NoSuchMethodException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchMethodException(owner.getClass().getName() + '#' + methodName);
    }

    private static Object invokeStaticNoArgSafely(String className, String methodName) {
        try {
            Class<?> owner = Class.forName(className);
            Method method = owner.getDeclaredMethod(methodName);
            method.setAccessible(true);
            return method.invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Integer asInteger(Object value) {
        return value instanceof Integer ? (Integer) value : null;
    }

    private static String asString(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static String safeText(Object value, int limit) {
        if (value == null) {
            return "null";
        }
        String text;
        try {
            text = String.valueOf(value);
        } catch (Throwable ignored) {
            return "<toString failed>";
        }
        return text.length() <= limit ? text : text.substring(0, limit) + "…";
    }

    private static String summarizeThrowable(Throwable throwable) {
        if (throwable == null) {
            return "null";
        }
        return throwable.getClass().getName() + ':' + safeText(throwable.getMessage(), 160);
    }

    private static String classNameOf(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    private static String signature(Executable executable) {
        return executable.getDeclaringClass().getName() + "#" + executable.getName();
    }

    private void info(String message) {
        log(Log.INFO, TAG, message);
    }

    private void error(String message, Throwable throwable) {
        log(Log.ERROR, TAG, message, throwable);
    }
}
