# libs/

这个目录用于**本地离线构建**，里面的 jar 属于各自作者，**不随本仓库分发**（已被 `.gitignore` 排除）。

构建前请自行放入以下三个文件（文件名需完全一致，`build.gradle` 按名引用）：

| 文件名 | 来源 |
|---|---|
| `[机械动力] create-1.21.1-6.0.10.jar` | Create 6.0.10 for 1.21.1 |
| `[机械动力：附魔工业] create-enchantment-industry-2.5.4b.jar` | Create: Enchantment Industry 2.5.4b |
| `[机械动力：龙+] CreateDragonsPlus-1.11.9.jar` | Create: Dragons Plus 1.11.9 |

它们只用于编译期类型解析（`compileOnly`），不会被打进产出的 jar。

如果你手上的版本号不同，改 `build.gradle` 里 `dependencies` 段的三个文件名即可，
编译本身只需要附魔工业的类名不变。

> 注意：本 mod 的运行时逻辑**全部通过反射**访问附魔工业的类，
> 因此产物不携带对上述任何 jar 的硬依赖，换版本通常也不影响运行。
