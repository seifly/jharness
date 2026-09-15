package cn.seifly.jharness.plugin.app.core;

import org.pf4j.PluginClassLoader;
import org.pf4j.PluginDescriptor;
import org.pf4j.PluginLoader;
import org.pf4j.PluginManager;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 扁平目录插件加载器：允许插件目录直接指向 {@code target/classes}，免去 {@code classes/} 嵌套。
 *
 * <p>与 PF4J 内置加载器的区别：
 * <ul>
 *   <li>{@code DefaultPluginLoader} 只把插件目录下的 {@code classes/} 与 {@code lib/} 加入 classpath，
 *       因此必须维持「classes/ + lib/ + plugin.properties」的目录约定；</li>
 *   <li>本加载器支持两种开发期形态：
 *       <ul>
 *         <li>形态 A：Maven 模块目录（{@code spring.pf4j.path} 直接指向 {@code plugins/}），
 *             以 {@code target/classes} 作为 classpath 根；</li>
 *         <li>形态 B：插件目录链接直接指向 {@code target/classes}（免 classes/ 嵌套），
 *             目录本身即 classpath 根，同时兼容旧结构（存在 {@code classes/} 子目录时一并加入）。</li>
 *       </ul>
 *   </li>
 *   <li>支持插件私有 {@code lib/} 与共享 {@code .lib/} 依赖目录。</li>
 * </ul>
 *
 * <p>配套目录形态（开发期）：
 * <pre>
 * plugins/                                     (spring.pf4j.path)
 * ├── hello-plugin/
 * │   └── target/classes/                      (形态 A：Maven 模块，含 plugin.properties)
 * ├── world-plugin/
 * │   └── target/classes/
 * └── .lib/commons-lang3-3.14.0.jar            (共享第三方依赖，可选；隐藏目录避开插件扫描)
 *
 * runtime-plugins/                             (形态 B：目录链接指向 target/classes)
 * ├── hello-plugin -&gt; plugins/hello-plugin/target/classes   (目录链接 / Windows Junction)
 * ├── world-plugin -&gt; plugins/world-plugin/target/classes
 * └── .lib/commons-lang3-3.14.0.jar
 * </pre>
 *
 * <p>描述符查找由 {@code MavenModulePluginDescriptorFinder} 完成：
 * Maven 会把 {@code src/main/resources/plugin.properties} 复制进 {@code target/classes}，
 * 因此两种形态下描述符均可定位。
 */
@Slf4j
public class FlatDirectoryPluginLoader implements PluginLoader {

    private final PluginManager pluginManager;

    public FlatDirectoryPluginLoader(PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    /**
     * 适用于「目录形态」的插件路径：目录且存在描述符（plugin.properties 或 MANIFEST.MF）。
     * 支持两种形态：
     * <ul>
     *   <li>Maven 模块目录：plugins/&lt;module&gt;/target/classes/plugin.properties；</li>
     *   <li>直接目录：目录本身即 classpath 根（链接指向 target/classes 或含 plugin.properties）。</li>
     * </ul>
     * 显式排除共享依赖目录（{@code .lib} / {@code lib}），避免被误认为插件。
     */
    @Override
    public boolean isApplicable(Path pluginPath) {
        if (pluginPath == null || !Files.isDirectory(pluginPath)) {
            return false;
        }
        Path fileName = pluginPath.getFileName();
        if (fileName != null
                && (".lib".equals(fileName.toString()) || "lib".equals(fileName.toString()))) {
            return false;
        }
        // 形态 A：Maven 模块目录（spring.pf4j.path 指向 plugins/）
        if (Files.exists(pluginPath.resolve("target/classes/plugin.properties"))) {
            return true;
        }
        // 形态 B/C：目录本身即 classpath 根
        return Files.exists(pluginPath.resolve("plugin.properties"))
                || Files.exists(pluginPath.resolve("META-INF/MANIFEST.MF"));
    }

    @Override
    public ClassLoader loadPlugin(Path pluginPath, PluginDescriptor pluginDescriptor) {
        PluginClassLoader pluginClassLoader = new PluginClassLoader(
                pluginManager, pluginDescriptor, getClass().getClassLoader());
        // 形态 A：Maven 模块目录 → target/classes 作为 classpath 根
        Path mavenClasses = pluginPath.resolve("target/classes");
        if (Files.isDirectory(mavenClasses)
                && Files.exists(mavenClasses.resolve("plugin.properties"))) {
            pluginClassLoader.addFile(mavenClasses.toFile());
        } else {
            // 形态 B/C：插件目录本身即 classpath 根（目录链接指向 target/classes）
            pluginClassLoader.addFile(pluginPath.toFile());
            // 兼容旧结构：存在 classes/ 子目录时一并加入
            Path classesDir = pluginPath.resolve("classes");
            if (Files.isDirectory(classesDir)) {
                pluginClassLoader.addFile(classesDir.toFile());
            }
        }
        // 插件私有依赖：插件目录下 lib/*.jar（兼容旧结构）
        addLibJars(pluginClassLoader, pluginPath.resolve("lib"));
        // 共享依赖：与插件目录同级（runtime-plugins/.lib/*.jar），如 commons-lang3。
        // 用隐藏目录名「.lib」，使 DefaultPluginRepository 的 HiddenFilter 不会把它列为插件候选。
        Path parent = pluginPath.getParent();
        if (parent != null) {
            addLibJars(pluginClassLoader, parent.resolve(".lib"));
        }
        return pluginClassLoader;
    }

    private void addLibJars(PluginClassLoader pluginClassLoader, Path libDir) {
        if (libDir == null || !Files.isDirectory(libDir)) {
            return;
        }
        List<Path> jars = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(libDir)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (name.endsWith(".jar") || name.endsWith(".zip")) {
                    jars.add(entry);
                }
            }
        } catch (IOException e) {
            log.warn("读取插件依赖目录 {} 失败: {}", libDir, e.getMessage());
        }
        if (!jars.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("插件依赖目录 {} 加入 classpath: {}", libDir, jars);
            }
            for (Path jar : jars) {
                pluginClassLoader.addFile(jar.toFile());
            }
        }
    }
}
