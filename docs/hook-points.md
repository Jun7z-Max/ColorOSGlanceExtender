# Hook 点与运行时不变量

本文记录 `0.1.11` 在已验证 ColorOS 组件上的运行链。类名和字段名来自目标版本，系统更新后可能发生变化。

## 已验证组件

```text
com.coloros.assistantscreen 17.10.120
com.oplus.pantanal.ums 16.59.4
Android 16 / API 36
```

## 作用域

```text
com.coloros.assistantscreen
com.oplus.pantanal.ums
```

模块仅在两个包的主进程安装 Hook。目标类不存在、ClassLoader 未就绪或结构不匹配时保持原厂行为。

## 核心流程

```mermaid
flowchart TD
    A[UMS 枚举 AppWidgetProviderInfo] --> B[记录当前 Provider 快照]
    B --> C[原厂云配置进入 ConfigRepository]
    C --> D[追加一个临时触发配置]
    D --> E[UMS 自然执行 d(boolean) 重建]
    E --> F[按当前 Provider 动态生成内存配置]
    F --> G[执行原厂匹配与缓存重建]
    G --> H[finally 清理合成云配置]
    H --> I[DAO 继续读取原厂配置]
    I --> J[速览读取 UMS 输出并使用原生 Host]
```

## UMS Hook 点

| 阶段 | 类/方法 | 行为 |
|---|---|---|
| Provider 快照 | `repository.e#a()` | 原方法返回后复制当前 `AppWidgetProviderInfo` 列表，仅用于动态生成 |
| 云配置入口 | `OperationWidgetConfigRepository#c(List)` | 必要时追加一个临时触发配置，让原厂自然进入重建流程 |
| 配置重建 | `OperationWidgetConfigRepository#d(boolean)` | 调用期间临时补齐动态配置；原方法结束后在 `finally` 中清理 |

## 速览 Hook 点

速览侧只保留 resident bridge 必需的两个 Hook：

- `FA.P::<init>` / `FA.P#x(...)`：捕获并刷新 resident service 引用；
- `AssistantContentProvider#queryAddCardState(Bundle)`：仅对通过 UID、type 和参数联合认证的 UMS 请求追加 bridge v2 状态。

其他 Provider 列表、Repository、AppWidget ID、HostView、`RemoteViews` 和 Launcher IPC 观测 Hook 均已移除。

## 动态配置生成

每次重建基于当前 UMS Provider 快照：

1. 仅保留当前用户；
2. 仅保留主屏幕类别；
3. 去除重复组件；
4. 跳过已有原厂 Operation Widget 配置的组件；
5. 按包名生成稳定 group；
6. 按组件名生成稳定 type；
7. 根据 Widget 尺寸映射到三种 ColorOS 卡片规格；
8. 保留配置 Activity，由原厂 Host 生命周期处理；
9. 单次最多生成 512 条配置。

应用列表不包含硬编码白名单。安装或卸载应用后，下一次 UMS Provider 刷新会使用新的实时列表。

## 名称与图标

### 名称

优先复用原厂 `groupTitle`。新增分组通过 `PackageManager.getApplicationLabel()` 动态读取名称，并立即转换为普通字符串；读取失败时回退到包名，保证标题不为空。

### 图标

新增分组使用：

```text
content://io.github.colorosglance.extender.icons/app/<packageName>
```

`IconContentProvider` 动态读取公开应用图标，渲染为 256×256 PNG，通过 pipe 输出，并使用 4 MiB 内存 LRU。Provider 只读，不写文件。

## 数据安全不变量

功能修改必须同时满足：

1. `OperationWidgetConfigRepository#d` 输出包含动态 Provider；
2. `finally` 清理后云配置事实源恢复到原始数量；
3. DAO 输入不包含模块 marker/type 区间；
4. UMS 数据库与验证前基线保持一致；
5. 未点击添加卡片前，不出现新的 AppWidget ID、绑定或 Host；
6. 不主动调用 Repository 更新、Subject `onNext()`、ContentProvider 通知或配置重放方法；
7. bridge、Provider 快照或反射结构异常时 fail-closed；
8. 速览和 UMS 不出现模块导致的崩溃或 ANR。

## 系统升级检查

适配新版本时依次检查：

1. 两个作用域包名是否变化；
2. UMS Provider Repository 是否仍返回 `AppWidgetProviderInfo`；
3. ConfigRepository 的 `c(List)` / `d(boolean)` 是否仍是当前事实链；
4. Operation Widget 构造器参数和 raw converter 是否变化；
5. 云配置事实源字段、DAO 保存时序和清理点是否变化；
6. 速览 resident 字段和 Binder 调用身份是否仍可认证；
7. 图标加载器是否仍支持 `content://` URI；
8. 完成数据库、AppWidget ID、崩溃和 ANR 回归验证。
