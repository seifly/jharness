package cn.seifly.jharness.plugin.app.core;

import org.pf4j.CompoundPluginLoader;
import org.pf4j.CompoundPluginRepository;
import org.pf4j.DefaultPluginManager;
import org.pf4j.JarPluginLoader;
import org.pf4j.JarPluginRepository;
import org.pf4j.PluginAlreadyLoadedException;
import org.pf4j.PluginDependency;
import org.pf4j.PluginDescriptor;
import org.pf4j.PluginDescriptorFinder;
import org.pf4j.PluginLoader;
import org.pf4j.PluginRepository;
import org.pf4j.PluginRuntimeException;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 自定义 PluginManager：默认实现基础上，接入 Spring 扩展工厂。
 *
 * <p>这是「Spring Boot 3 + 原生 PF4J」的标准集成点，
 * 与官方 pf4j-spring 的 SpringPluginManager 思路一致，但无需额外依赖。
 */
@Slf4j
public class SpringPluginManager extends DefaultPluginManager {

    private final ApplicationContext applicationContext;

    public SpringPluginManager(Path pluginsRoot, ApplicationContext applicationContext) {
        super(pluginsRoot);
        this.applicationContext = applicationContext;
        // 注意：模板方法 createExtensionFactory() 在 super() 构造期间即被调用，
        // 此时子类字段尚未初始化，直接赋值会得到 applicationContext=null 的工厂。
        // 因此必须在 super() 返回后显式覆盖 extensionFactory 字段
        // （ExtensionFinder 通过 PluginManager 动态获取工厂，覆盖后立即生效）。
        this.extensionFactory = new SpringExtensionFactory(applicationContext);
        // 插件工厂同理：super() 构造期间 createPluginFactory() 已被调用且拿到 null 上下文，
        // 这里显式覆盖，使 Plugin 子类也能 @Autowired 主应用 Bean。
        this.pluginFactory = new SpringPluginFactory(applicationContext);
    }

    /**
     * 只保留 properties 描述符查找（并支持 Maven 模块目录形态）：
     * <ul>
     *   <li>不引入内置 ManifestPluginDescriptorFinder——开发期插件目录里没有
     *       META-INF/MANIFEST.MF，它会抛「Cannot find the manifest path」ERROR 噪音；</li>
     *   <li>{@link MavenModulePluginDescriptorFinder} 在模块根目录找不到 plugin.properties 时，
     *       回退到 target/classes/，使 {@code spring.pf4j.path} 可直接指向源码聚合模块 plugins/。</li>
     * </ul>
     * jar / zip 插件包仍按 properties 查找（Maven 会将 plugin.properties 打进包内）。
     */
    @Override
    protected PluginDescriptorFinder createPluginDescriptorFinder() {
        return new MavenModulePluginDescriptorFinder();
    }

    /**
     * 插件仓库：扫描插件根目录下的候选插件。
     * <ul>
     *   <li>{@link MavenModulePluginRepository}：默认目录扫描基础上，排除 Maven 内部目录
     *       （target / src），避免聚合模块构建产物被误当插件；</li>
     *   <li>{@link JarPluginRepository}：保留对插件根目录下 jar / zip 插件包的自动发现。</li>
     * </ul>
     */
    @Override
    protected PluginRepository createPluginRepository() {
        return new CompoundPluginRepository()
                .add(new MavenModulePluginRepository(getPluginsRoots()))
                .add(new JarPluginRepository(getPluginsRoots()));
    }

    /**
     * 替换默认的 loader 组合（JarPluginLoader + DefaultPluginLoader）：
     * <ul>
     *   <li>{@link FlatDirectoryPluginLoader}：开发期插件目录可直接指向 target/classes 或
     *       Maven 模块目录（免 classes/ 嵌套），由它决定 classpath 根；</li>
     *   <li>{@link JarPluginLoader}：保留 jar / zip 插件包加载能力。</li>
     * </ul>
     * 不保留内置 DefaultPluginLoader，避免把共享依赖目录（.lib/lib）误当插件。
     */
    @Override
    protected PluginLoader createPluginLoader() {
        return new CompoundPluginLoader()
                .add(new FlatDirectoryPluginLoader(this))
                .add(new JarPluginLoader(this));
    }

    /**
     * 幂等加载：PF4J 3.15 的 {@code loadPlugins()} 对插件目录中的每个路径直接调用
     * {@code loadPluginFromPath}，不会跳过已加载的插件——插件已加载时会抛
     * {@link PluginAlreadyLoadedException} 并打印 ERROR 堆栈（"Cannot load plugin ..."）。
     *
     * <p>这里捕获该异常，视为幂等成功并返回已加载的 {@link PluginWrapper}，
     * 避免重复加载报错噪音。{@code loadPlugins()}（load / reload 接口）与
     * {@code loadPlugin(Path)}（upload 接口）等所有入口均经由此方法，一并受益。
     */
    @Override
    protected PluginWrapper loadPluginFromPath(Path pluginPath) {
        try {
            return super.loadPluginFromPath(pluginPath);
        } catch (PluginAlreadyLoadedException e) {
            log.info("插件 '{}' 已加载，跳过重复加载: {}", e.getPluginId(), e.getPluginPath());
            return getPlugin(e.getPluginId());
        }
    }

    /**
     * 按插件依赖（plugin.properties 中 plugin.dependencies）拓扑排序后加载。
     *
     * <p>PF4J 3.15 默认按插件目录扫描顺序逐个 {@code loadPluginFromPath}，
     * 不保证依赖插件先加载；而 {@code DefaultPluginManager#createPluginClassLoader}
     * 需要依赖插件已加载，才会把依赖插件的类加载器挂到父链上。
     * 跨插件编译期引用（如 workflow-plugin 引用 models-plugin 的 ModelsConfig、
     * tools-plugin 引用 skills-plugin 的 SkillsLoader）正是依赖该机制在运行期解析，
     * 否则会抛出 {@code NoClassDefFoundError}。
     */
    @Override
    public void loadPlugins() {
        List<Path> pluginPaths = pluginRepository.getPluginPaths();
        if (pluginPaths.isEmpty()) {
            return;
        }

        // 1. 读取每个候选插件的 descriptor，建立 pluginId -> path 及依赖关系
        Map<String, Path> pathById = new LinkedHashMap<>();
        Map<String, Set<String>> dependencies = new HashMap<>();
        for (Path pluginPath : pluginPaths) {
            try {
                PluginDescriptor descriptor = pluginDescriptorFinder.find(pluginPath);
                pathById.put(descriptor.getPluginId(), pluginPath);
                // descriptor.getDependencies() 返回 List<PluginDependency>，需提取 pluginId
                Set<String> depIds = new LinkedHashSet<>();
                for (PluginDependency dep : descriptor.getDependencies()) {
                    depIds.add(dep.getPluginId());
                }
                dependencies.put(descriptor.getPluginId(), depIds);
            } catch (PluginRuntimeException e) {
                log.warn("读取插件描述失败，按原顺序加载: {} ({})", pluginPath, e.getMessage());
                pathById.put(pluginPath.toString(), pluginPath);
                dependencies.put(pluginPath.toString(), Set.of());
            }
        }

        // 2. 按依赖做拓扑排序（Kahn），保证依赖插件先于使用方加载
        List<String> orderedIds = topoSortByDependencies(pathById.keySet(), dependencies);

        // 3. 按依赖顺序逐个加载
        for (String pluginId : orderedIds) {
            Path pluginPath = pathById.get(pluginId);
            if (pluginPath == null) {
                continue;
            }
            try {
                loadPluginFromPath(pluginPath);
            } catch (PluginRuntimeException e) {
                log.error("加载插件失败: {} ({})", pluginId, pluginPath, e);
            }
        }

        // 4. 解析已加载插件：把所有插件从 CREATED 推进到 RESOLVED。
        // PF4J 3.15 的 DefaultPluginManager.loadPlugins() 会自己调用 resolvePlugins()，
        // 但我们重写了 loadPlugins() 且完全跳过了父类实现，所以必须显式调用，
        // 否则所有插件都停在 CREATED 状态，startPlugins() 看到 RESOLVED==false 就不会启动它们。
        super.resolvePlugins();
    }

    /**
     * 拓扑排序：入度为零（无依赖或依赖已就绪）的插件优先。
     * 依赖插件不在插件目录中（未部署）时视为无依赖；
     * 出现依赖环或无法解析的依赖时，剩余插件按原顺序补齐，避免加载中断。
     */
    private List<String> topoSortByDependencies(Collection<String> pluginIds,
                                                Map<String, Set<String>> dependencies) {
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (String pluginId : pluginIds) {
            inDegree.put(pluginId, 0);
        }
        for (String pluginId : pluginIds) {
            for (String dependency : dependencies.getOrDefault(pluginId, Set.of())) {
                if (!inDegree.containsKey(dependency)) {
                    continue; // 依赖插件未部署，忽略
                }
                inDegree.put(pluginId, inDegree.get(pluginId) + 1);
                dependents.computeIfAbsent(dependency, key -> new ArrayList<>()).add(pluginId);
            }
        }

        Deque<String> ready = new ArrayDeque<>();
        for (String pluginId : pluginIds) {
            if (inDegree.get(pluginId) == 0) {
                ready.add(pluginId);
            }
        }

        List<String> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            String pluginId = ready.poll();
            ordered.add(pluginId);
            for (String dependent : dependents.getOrDefault(pluginId, List.of())) {
                int nextDegree = inDegree.get(dependent) - 1;
                inDegree.put(dependent, nextDegree);
                if (nextDegree == 0) {
                    ready.add(dependent);
                }
            }
        }

        if (ordered.size() < pluginIds.size()) {
            log.warn("检测到插件依赖环或无法解析的依赖，剩余插件按原顺序补齐加载");
            for (String pluginId : pluginIds) {
                if (!ordered.contains(pluginId)) {
                    ordered.add(pluginId);
                }
            }
        }
        return ordered;
    }

    /**
     * 查询插件状态（PF4J 3.15 接口中无此方法，这里补充）：
     * 已加载时取 {@link PluginWrapper#getPluginState()}；
     * 未加载时根据状态文件区分「已停用」与「已卸载」。
     */
    public PluginState getPluginState(String pluginId) {
        PluginWrapper wrapper = getPlugin(pluginId);
        if (wrapper != null) {
            return wrapper.getPluginState();
        }
        return pluginStatusProvider.isPluginDisabled(pluginId) ? PluginState.DISABLED : PluginState.UNLOADED;
    }
}
