# Mending Forger Fix

让 **Create: Enchantment Industry（机械动力：附魔工业）的烈焰人锻造室**能够把两本**经验修补 I**
合并成**经验修补 II**，用于配合 Quark 等模组对经验修补的平衡性改动。

## 为什么需要这个 mod

附魔工业把"经验修补不能升级"**硬编码在数据映射（data map）里**，
在 `CEIDataMaps#generate` 中给 `minecraft:mending` 和 `minecraft:infinity` 写死了
`enchantmentAndForgerExtension(0, 0)`：

```java
provider.builder(ENCHANTMENT_PROCESSING_RULES)
        .add(MENDING,  EnchantmentProcessingRule.enchanterAndForgerExtension(0, 0), false)
        .add(INFINITY, EnchantmentProcessingRule.enchanterAndForgerExtension(0, 0), false);
```

这条"每附魔专属规则"同时**架空了配置文件**。`EnchantmentProcessingRules#blazeForgerLevelExtension`
的取值顺序是：

```java
var rule = enchantment.getData(CEIDataMaps.ENCHANTMENT_PROCESSING_RULES);
if (rule != null) { ... return value.get(); }              // ← 经验修补在这里就被 0 拦住了
return CEIConfig.enchantments().blazeForgerMaxLevelExtension.get();   // ← 永远走不到
```

而该配置项的注释本身也写明了：*"when no per-enchantment processing rule exists"*。
所以**改配置文件无效**。

改数据包（datapack）也**不可靠**：NeoForge 的 `DataMapLoader#buildDataMap` 对多个数据包的同名条目
走 `DataMapValueMerger.defaultMerger()` 合并，胜负取决于资源栈顺序，
实测中世界数据包并没有拿到优先级。

## 本 mod 的做法

NeoForge 把数据映射装进注册表上一个**普通的可变 Map**
（`DataMapLoader#apply` → `BaseMappedRegistry#dataMaps`，是个 `IdentityHashMap`），
装完之后触发 `DataMapsUpdatedEvent`。本 mod 监听该事件，**就地替换**那条规则：

```
minecraft:mending  ->  blaze_forger = 1, blaze_enchanter = 0
```

于是上限变成 `原版上限(1) + 1 = 2`，两本经验修补 I 合并即可得到 II。
这条路径是确定性的，不依赖数据包优先级，也不需要改动任何原 mod 文件。

实现上**没有任何对 CEI 的编译期依赖**：规则对象通过反射构造，
失败时只打日志、不会让游戏崩溃。

## 安装

1. 把 `mending_forger_fix-1.0.0.jar` 放进 `mods/`
2. 需要 Create 6.0.10+ 与 Create: Enchantment Industry 2.5.0+
3. 启动游戏即可生效（**服务端**决定行为，客户端装不装都不影响）

进游戏后可留意日志里这一行，确认已生效：

```
[mending_forger_fix] minecraft:mending -> blaze_forger=1, blaze_enchanter=0 (Mending II enabled on the Blaze Forger)
```

失效时也会有明确告警，例如：

```
[mending_forger_fix] Data map 'create_enchantment_industry:enchantment_processing/rules' not found;
nothing changed. The Blaze Forger will keep CEI's vanilla level caps.
```

## 配置

配置文件：`config/mending_forger_fix-common.toml`

```toml
enchantmentExtensions = ["minecraft:mending=1"]
```

格式为 `"<附魔id>=<锻造室扩展>[,<附魔器扩展>]"`：

| 值 | 含义 |
|---|---|
| `0` | 保持原版上限（CEI 对经验修补、无限的默认值） |
| `1` | 允许高出原版 1 级（经验修补 I + I → II） |
| 省略第二项 | 附魔器沿用锻造室的数值 |

想同时放开烈焰附魔器直接产出经验修补 II：

```toml
enchantmentExtensions = ["minecraft:mending=1,1"]
```

想顺带放开无限（原版上限 1，可到无限 II）：

```toml
enchantmentExtensions = ["minecraft:mending=1", "minecraft:infinity=1"]
```

> 注意：**超过原版上限需要"超越"模式**。
> `blaze_forger = 1` 时上限正好是 2，普通烈焰即可达成；
> 若要更高（如经验修补 III 需要扩展 2），则必须用 SEETHING 加热的超越模式。

## 从源码构建

依赖本机工具链缓存，**完全离线构建**（本机无法访问 `maven.neoforged.net`）：

```powershell
$env:GRADLE_USER_HOME = "C:\Users\<你>\Documents\DSH\Create Test1\.toolchain\gh"
$env:JAVA_TOOL_OPTIONS = "-Dfile.encoding=UTF-8 -Djava.net.preferIPv4Stack=true"
& "C:\Users\<你>\Documents\DSH\Create Test1\.toolchain\gradle\gradle-8.14.2\bin\gradle.bat" `
    --project-dir . --offline --no-daemon --console=plain build
```

产物：`build/libs/mending_forger_fix-1.0.0.jar`，同时会复制一份到 `libs-archive/`。

`libs/` 里的三个 jar（Create、附魔工业、龙+）来自目标整合包，仅在编译期使用
（`compileOnly`），不会被打进产物，产物也不含对它们的硬依赖。

### 两个容易踩的坑（已修）

1. **`build.gradle` 里必须有 `neoForge { mods { "${mod_id}" { sourceSet(sourceSets.main) } } }`。**
   少了这个块，开发环境里 mod 会出现在模组列表中、但 `@Mod` 构造函数**永远不会被调用**
   （ModDevGradle 不知道该把哪些源码绑定到哪个 mod）。生产环境不受影响。
2. **不要在 mod 构造函数里调用 `ConfigValue#get()`。**
   此时配置尚未加载，会抛异常并导致 `Failed to create mod instance`。
   构造函数用内置默认值播种，真实值由 `ModConfigEvent.Loading` / `Reloading` 送达。

## 运行验证

在 NeoForge 21.1.250 开发服务器（加载 Create 6.0.10 + 附魔工业 2.5.4b + 龙+ 1.11.9）实测通过：

```
[mending_forger_fix] minecraft:mending -> blaze_forger=1, blaze_enchanter=0
```

> 该开发服务器在启动完成后会抛 `IllegalStateException: Failed to initialize server` 崩溃，
> 这是**开发环境跑不完整整合包**导致的，与本事无关：同样的崩溃在没有本 mod 时也会出现，
> 且崩溃报告里没有本 mod 的堆栈。正式环境（完整整合包）不受影响。

## 许可

MIT
