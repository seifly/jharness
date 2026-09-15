package cn.seifly.jharness.plugin.app.core;

import org.pf4j.PropertiesPluginDescriptorFinder;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 适配「Maven 模块目录」作为插件目录的形态：
 * 当 spring.pf4j.path 直接指向源码聚合模块（如 plugins/）时，
 * 每个插件的 plugin.properties 位于 target/classes/ 而非模块根目录，
 * 这里在根目录找不到描述符时回退到 target/classes/plugin.properties。
 *
 * <p>目录形态（开发期）：
 * <pre>
 * plugins/
 * ├── hello-plugin/
 * │   ├── src/main/resources/plugin.properties   (源)
 * │   └── target/classes/plugin.properties        (Maven 复制产物，运行时读取)
 * ├── world-plugin/
 * │   └── target/classes/plugin.properties
 * └── .lib/commons-lang3-3.14.0.jar               (共享第三方依赖，可选)
 * </pre>
 *
 * <p>jar / zip 插件包形态不受影响：仍按父类逻辑在包内定位 plugin.properties。
 */
public class MavenModulePluginDescriptorFinder extends PropertiesPluginDescriptorFinder {

    @Override
    protected Path getPropertiesPath(Path pluginPath, String propertiesFileName) {
        Path path = super.getPropertiesPath(pluginPath, propertiesFileName);
        // 目录形态且根下无描述符：Maven 模块会把 resources 复制进 target/classes
        if (Files.isDirectory(pluginPath) && !Files.exists(path)) {
            Path mavenPath = pluginPath.resolve("target/classes").resolve(propertiesFileName);
            if (Files.exists(mavenPath)) {
                return mavenPath;
            }
        }
        return path;
    }
}
