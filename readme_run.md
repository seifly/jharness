# 插件调试模式说明（扁平目录加载器，方案 C）

## 1. 它是什么

应用通过 `spring.pf4j.path` 指定插件加载目录，现支持两种「零拷贝」开发期形态：

- **形态 A（推荐）：直接指向源码聚合模块 `plugins/`**——每个插件子模块的 `target/classes` 即插件 classpath 根，**不需要任何软链/Junction**，改代码 → 重新编译 → 热重载即可；
- **形态 B（备选）：`runtime-plugins/` 目录链接**——`runtime-plugins/<name>` 软链/Junction 指向 `target/classes`。

两种形态都由自定义加载器
`core.cn.seifly.jharness.plugin.app.FlatDirectoryPluginLoader` 实现：

- 形态 A 把 `target/classes` 作为 classpath 根；形态 B 把插件目录本身作为 classpath 根；
- 描述符 `plugin.properties` 由 Maven 自动复制进 `target/classes`，无需单独维护；
- 共享第三方依赖放在隐藏目录 `.lib/`（隐藏名使 PF4J 的插件仓库不会把它当插件扫描）。

## 2. 形态 A：spring.pf4j.path = plugins（推荐）

`application.yml`：

```yaml
spring:
  pf4j:
    path: plugins
```

目录结构（**无需任何链接命令**）：

```
plugins/                                  ← spring.pf4j.path
├── pom.xml                               (聚合模块，PF4J 不扫描文件)
├── hello-plugin/
│   ├── src/main/resources/plugin.properties   (源描述符)
│   └── target/classes/                        ← 运行时 classpath 根（Maven 编译产物）
│       ├── plugin.properties
│       └── cn/seifly/plugin/plugins/hello/…
├── world-plugin/
│   └── target/classes/
└── .lib/
    └── commons-lang3-3.14.0.jar          (共享第三方依赖，仅 hello-plugin 使用)
```

**调试循环（全平台一致，零链接零权限）：**

```bash
# 1) 改代码后重新编译
mvn -f plugins/pom.xml compile

# 2) 热重载（或重启应用）
curl -X POST http://localhost:8080/api/plugins/hello-plugin/enable
```

新环境 clone 后**什么都不用做**（`plugins/` 随 git 提交，target 编译一次即可）——这正是形态 A 相对形态 B 的最大优势。

### 前提：运行目录

`path: plugins` 是相对路径，按应用**工作目录**解析：

- IDEA 运行 `plugin-app`：Working directory 必须设为 `$PROJECT_DIR$`（项目根），否则会解析到 `plugin-app/plugins`；
- 命令行：`mvn -pl plugin-app spring-boot:run` 时相对路径基于 `plugin-app/`，需用绝对路径覆盖：
  ```bash
  mvn -pl plugin-app spring-boot:run -Dspring-boot.run.arguments="--spring.pf4j.path=$PWD/plugins"
  ```

## 3. 形态 B：runtime-plugins 目录链接（备选）

适用：不想改 `application.yml`、或需要把插件目录与源码分开时。

```
runtime-plugins/
├── hello-plugin -> plugins/hello-plugin/target/classes   (目录链接 / Windows Junction)
├── world-plugin -> plugins/world-plugin/target/classes
└── .lib/
    └── commons-lang3-3.14.0.jar
```

### macOS / Linux

```bash
# 0) 前提：先编译出 target/classes（IDEA Ctrl+F9 或 mvn 均可）
cd /Users/seifly/work/java/seifly-plugin-framework
mvn -f plugins/pom.xml clean package

# 1) 清理并建目录（.lib 是隐藏目录）
rm -rf runtime-plugins/hello-plugin runtime-plugins/world-plugin
mkdir -p runtime-plugins/.lib

# 2) 目录链接（一律用绝对路径）
ln -sfn $PWD/plugins/hello-plugin/target/classes runtime-plugins/hello-plugin
ln -sfn $PWD/plugins/world-plugin/target/classes runtime-plugins/world-plugin

# 3) 共享依赖
cp ~/.m2/repository/org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.jar runtime-plugins/.lib/
```

### Windows（PowerShell）

`classes` 链接用 **Junction**（`New-Item -ItemType Junction`）——**不需要管理员权限、不需要开发者模式**：

```powershell
cd D:\path\to\project
# 前提：先编译出 target/classes

New-Item -ItemType Junction -Path runtime-plugins\hello-plugin -Target "$PWD\plugins\hello-plugin\target\classes"
New-Item -ItemType Junction -Path runtime-plugins\world-plugin -Target "$PWD\plugins\world-plugin\target\classes"
New-Item -ItemType Directory -Force -Path runtime-plugins\.lib
Copy-Item "$HOME\.m2\repository\org\apache\commons\commons-lang3\3.14.0\commons-lang3-3.14.0.jar" runtime-plugins\.lib\
```

### 新环境是否需要再执行？

**需要。** `runtime-plugins/` 在 `.gitignore` 中（`git check-ignore runtime-plugins` 命中），新环境必须重新执行上述命令。

## 4. 实现机制（方案 C）

| 组件 | 说明 |
| --- | --- |
| `FlatDirectoryPluginLoader` | 自定义 `PluginLoader`：形态 A 以 `target/classes` 为 classpath 根；形态 B 以插件目录本身为根（兼容旧 `classes/` 子目录）；加载插件私有 `lib/` 与共享 `.lib/` 依赖 |
| `MavenModulePluginDescriptorFinder` | 继承 `PropertiesPluginDescriptorFinder`：目录下找不到 `plugin.properties` 时回退到 `target/classes/`，使 `spring.pf4j.path` 可直指源码聚合模块 |
| `MavenModulePluginRepository` | 继承 `DefaultPluginRepository`：排除 Maven 内部目录（`target` / `src`），避免聚合模块构建产物被误当插件解析描述符打 ERROR |
| `SpringPluginManager.createPluginLoader()` | 覆盖默认组合为 `Compound[FlatDirectoryPluginLoader, JarPluginLoader]`，移除内置 `DefaultPluginLoader`（避免把依赖目录误当插件） |
| `SpringPluginManager.createPluginDescriptorFinder()` | 只保留 `MavenModulePluginDescriptorFinder`，跳过 `ManifestPluginDescriptorFinder`（target/classes 无 MANIFEST.MF，避免 ERROR 噪音） |
| `SpringPluginManager.createPluginRepository()` | `Compound[MavenModulePluginRepository, JarPluginRepository]`，保留 jar 插件包自动发现 |
| `.lib/` 隐藏目录 | 插件仓库 `HiddenFilter` 过滤隐藏文件，`.lib` 不会被列为插件候选 |

## 5. 注意事项

- **形态 A 无需任何链接**；形态 B 的目录链接一律用绝对路径（`$PWD` 或全路径），相对路径会按链接所在目录解析。
- 修改插件代码后，先重新编译（IDEA `Ctrl+F9` 或 `mvn -f plugins/pom.xml compile`）让 `target/classes` 更新，再重启应用或调用 `POST /api/plugins/{id}/enable` 热重载。
- 新增插件：在 `plugins/` 下建模块 → 编译 →（形态 A 直接生效 / 形态 B 加一条目录链接）；若带第三方依赖，把 jar 放入 `.lib/`。
- 停用插件会在插件根目录下生成 `disabled.txt` 停用标记；恢复后建议删除，避免下次启动跳过该插件。
- `plugin.properties` 里的中文必须保持 `\uXXXX` 转义（Java `Properties` 按 ISO-8859-1 解码，明文 UTF-8 会乱码）。

## 6. 验证

```bash
# 形态 A
curl http://localhost:8080/api/plugins          # path 字段应为 plugins/<name>，中文描述正常

# 形态 B
ls -la runtime-plugins/                         # 应为 lrwxr-xr-x（Windows 为 <JUNCTION>）

# 通用
curl http://localhost:8080/api/hello/say        # 接口正常（验证 .lib 依赖）
curl -X POST http://localhost:8080/api/plugins/hello-plugin/disable   # 接口 404
curl -X POST http://localhost:8080/api/plugins/hello-plugin/enable    # 接口恢复
```
