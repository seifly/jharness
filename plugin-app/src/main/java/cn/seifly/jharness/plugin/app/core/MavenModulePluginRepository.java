package cn.seifly.jharness.plugin.app.core;

import org.pf4j.DefaultPluginRepository;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Maven 模块目录形态的插件仓库：直接枚举 {@code pluginsRoot} 下的
 * <strong>所有一级子目录</strong>作为插件候选，
 * 不再依赖 PF4J 默认的 {@code DefaultPluginFinder}。
 *
 * <h3>为什么不能复用 DefaultPluginRepository 的默认枚举？</h3>
 * <p>PF4J 3.15 的 {@code DefaultPluginFinder} 只会把「根目录下存在
 * {@code classes/ | lib/ | plugin.properties | META-INF/MANIFEST.MF}」的目录视为
 * 合法插件候选；而 Maven 多模块工程里：
 * <pre>
 * plugins/                          &lt;- pluginsRoot
 * ├── hello-plugin/                 &lt;- 模块根，只有 src/、pom.xml、target/
 * │   └── target/classes/           &lt;- 真正的 classpath 根（含 plugin.properties）
 * └── models-plugin/
 *     └── target/classes/
 * </pre>
 * 每个子模块根目录并不满足 DefaultPluginFinder 的条件，
 * 因此 {@code DefaultPluginRepository#getPluginPaths()} 会返回空列表，
 * 导致 {@code loadPlugins()} 静默跳过全部插件。
 *
 * <h3>本类候选规则</h3>
 * <ul>
 *   <li>仅取 pluginsRoot 的<strong>一级子目录</strong>（不递归），避免把 target/src
 *       这类 Maven 内部目录误当插件；</li>
 *   <li>排除隐藏目录（{@code .lib}、{@code .DS_Store} 等）；</li>
 *   <li>排除名为 {@code target} / {@code src} 的目录；</li>
 *   <li>jar/zip 文件由同层注册的 {@link org.pf4j.JarPluginRepository} 负责发现。</li>
 * </ul>
 *
 * <p>最终描述符与 classpath 解析仍交由：
 * {@link MavenModulePluginDescriptorFinder}（回退到 target/classes/ 取 plugin.properties）
 * 与 {@link FlatDirectoryPluginLoader}（以 target/classes/ 作为 classpath 根）协作完成。
 */
@Slf4j
public class MavenModulePluginRepository extends DefaultPluginRepository {

    private final List<Path> pluginsRoots;

    public MavenModulePluginRepository(List<Path> pluginsRoots) {
        super(pluginsRoots);
        this.pluginsRoots = pluginsRoots;
    }

    @Override
    public List<Path> getPluginPaths() {
        List<Path> candidates = new ArrayList<>();
        for (Path root : pluginsRoots) {
            if (root == null || !Files.isDirectory(root)) {
                continue;
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
                for (Path entry : stream) {
                    if (isPluginCandidate(entry)) {
                        candidates.add(entry);
                    }
                }
            } catch (IOException e) {
                log.warn("扫描插件根目录 {} 失败: {}", root, e.getMessage());
            }
        }
        if (candidates.isEmpty()) {
            log.warn("未发现任何插件目录候选；若使用 Maven 多模块形态，请确认已执行 mvn compile " +
                    "（使各模块 target/classes/plugin.properties 生成），且 spring.pf4j.path 指向 plugins/ 聚合模块。");
        } else {
            if (log.isDebugEnabled()) {
                log.debug("发现 {} 个插件目录候选: {}", candidates.size(), candidates);
            }
        }
        return candidates;
    }

    private boolean isPluginCandidate(Path path) {
        if (path == null || !Files.isDirectory(path)) {
            return false;
        }
        String name = path.getFileName() != null ? path.getFileName().toString() : "";
        if (name.isEmpty()) {
            return false;
        }
        // 隐藏目录（.lib / .DS_Store 目录等）统一排除
        if (name.startsWith(".")) {
            return false;
        }
        // Maven 聚合模块自身的构建产物 / 源码根
        if ("target".equals(name) || "src".equals(name)) {
            return false;
        }
        return true;
    }
}
