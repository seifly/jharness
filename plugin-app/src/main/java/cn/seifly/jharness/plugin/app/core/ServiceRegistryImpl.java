package cn.seifly.jharness.plugin.app.core;

import cn.seifly.jharness.plugin.framework.service.Service;
import cn.seifly.jharness.plugin.framework.service.ServiceRegistration;
import cn.seifly.jharness.plugin.framework.service.ServiceRegistry;
import org.pf4j.PluginManager;
import org.pf4j.PluginState;
import org.pf4j.PluginStateEvent;
import org.pf4j.PluginStateListener;
import org.pf4j.PluginWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 主应用提供的 {@link ServiceRegistry} 实现。
 *
 * <p>基于 PF4J 的 PluginManager 维护注册项与来源插件的关联：
 * <ul>
 *   <li>{@link #register} 时通过服务实例的 ClassLoader 反查所属 pluginId（无需调用方显式传入）；</li>
 *   <li>插件停止时通过 {@link PluginStateListener} 兜底清理（即使 Plugin.stop() 忘记调 unregister）；</li>
 *   <li>线程安全：所有读写基于 {@link ConcurrentHashMap}。</li>
 * </ul>
 *
 * <p>注意生命周期：本 bean 由 {@link cn.seifly.jharness.plugin.app.config.Pf4jConfiguration#pluginManager}
 * 在 {@code startPlugins()} 之前调用 {@link #bind(PluginManager)} 注入 PluginManager，
 * 保证注册 / 清理 / 反查 pluginId 的能力在第一个插件 start() 时已就位。
 */
@Slf4j
@Component
public class ServiceRegistryImpl implements ServiceRegistry {

    private static final String UNKNOWN_PLUGIN_ID = "unknown";

    /** 服务契约 -> (name -> entry) */
    private final ConcurrentHashMap<Class<? extends Service>, ConcurrentHashMap<String, ServiceEntry>> registry =
            new ConcurrentHashMap<>();

    /** pluginId -> 已注册 entry 列表，用于插件停止时批量清理 */
    private final ConcurrentHashMap<String, List<ServiceEntry>> byPlugin = new ConcurrentHashMap<>();

    private volatile PluginManager pluginManager;

    /**
     * 由 {@link cn.seifly.jharness.plugin.app.config.Pf4jConfiguration} 在
     * {@code loadPlugins()} 之后、{@code startPlugins()} 之前调用。
     *
     * <p>在此注册 {@link PluginStateListener}：插件进入 STOPPED 状态时清理其注册项。
     */
    public void bind(PluginManager manager) {
        this.pluginManager = manager;
        manager.addPluginStateListener(new PluginStateListener() {
            @Override
            public void pluginStateChanged(PluginStateEvent event) {
                if (event.getPluginState() == PluginState.STOPPED) {
                    unregisterByPlugin(event.getPlugin().getPluginId());
                }
            }
        });
        log.info("ServiceRegistryImpl bound to PluginManager");
    }

    // ==================== ServiceRegistry API ====================

    @Override
    public <S extends Service> ServiceRegistration<S> register(Class<S> type, S service, String name) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (service == null) {
            throw new IllegalArgumentException("service must not be null");
        }
        String resolvedName = (name == null || name.isEmpty())
                ? service.getClass().getSimpleName() : name;
        String pluginId = detectPluginId(service);

        ServiceEntry entry = new ServiceEntry(type, service, resolvedName, pluginId);
        ConcurrentHashMap<String, ServiceEntry> byName =
                registry.computeIfAbsent(type, k -> new ConcurrentHashMap<>());
        ServiceEntry prev = byName.putIfAbsent(resolvedName, entry);
        if (prev != null) {
            // 同名覆盖：先标记旧的失效，再覆盖
            prev.valid.set(false);
            byName.put(resolvedName, entry);
            log.warn("Service with same name replaced: type={}, name={}, prev_pluginId={}, new_pluginId={}",
                    type.getSimpleName(), resolvedName, prev.pluginId, pluginId);
        }
        byPlugin.computeIfAbsent(pluginId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(entry);

        log.info("Service registered: type={}, name={}, pluginId={}",
                type.getSimpleName(), resolvedName, pluginId);
        return new RegistrationImpl<>(entry, this);
    }

    @Override
    public <S extends Service> Optional<S> get(Class<S> type) {
        ConcurrentHashMap<String, ServiceEntry> byName = registry.get(type);
        if (byName == null || byName.isEmpty()) {
            return Optional.empty();
        }
        // 取首个有效 entry
        for (ServiceEntry e : byName.values()) {
            if (e.valid.get()) {
                return Optional.of(type.cast(e.service));
            }
        }
        return Optional.empty();
    }

    @Override
    public <S extends Service> Optional<S> get(Class<S> type, String name) {
        if (name == null) {
            return Optional.empty();
        }
        ConcurrentHashMap<String, ServiceEntry> byName = registry.get(type);
        if (byName == null) {
            return Optional.empty();
        }
        ServiceEntry e = byName.get(name);
        return (e != null && e.valid.get()) ? Optional.of(type.cast(e.service)) : Optional.empty();
    }

    @Override
    public <S extends Service> List<S> getAll(Class<S> type) {
        ConcurrentHashMap<String, ServiceEntry> byName = registry.get(type);
        if (byName == null) {
            return Collections.emptyList();
        }
        List<S> result = new ArrayList<>();
        for (ServiceEntry e : byName.values()) {
            if (e.valid.get()) {
                result.add(type.cast(e.service));
            }
        }
        return result;
    }

    @Override
    public <S extends Service> List<String> getNames(Class<S> type) {
        ConcurrentHashMap<String, ServiceEntry> byName = registry.get(type);
        if (byName == null) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (ServiceEntry e : byName.values()) {
            if (e.valid.get()) {
                result.add(e.name);
            }
        }
        return result;
    }

    // ==================== 内部清理 ====================

    /**
     * 注销单个 entry。幂等。
     */
    private void unregisterEntry(ServiceEntry entry) {
        if (entry == null || !entry.valid.compareAndSet(true, false)) {
            return;
        }
        ConcurrentHashMap<String, ServiceEntry> byName = registry.get(entry.type);
        if (byName != null) {
            byName.remove(entry.name, entry);
            // 若该契约下已无任何注册项，移除空 map 防止内存累积
            if (byName.isEmpty()) {
                registry.remove(entry.type, byName);
            }
        }
        List<ServiceEntry> entries = byPlugin.get(entry.pluginId);
        if (entries != null) {
            entries.remove(entry);
        }
        log.info("Service unregistered: type={}, name={}, pluginId={}",
                entry.type.getSimpleName(), entry.name, entry.pluginId);
    }

    /**
     * 批量注销某插件下的全部注册项。插件 STOPPED 时由 PluginStateListener 触发。
     */
    private void unregisterByPlugin(String pluginId) {
        List<ServiceEntry> entries = byPlugin.remove(pluginId);
        if (entries == null) {
            return;
        }
        // 复制一份再清理，避免遍历过程中 unregisterEntry 修改列表
        List<ServiceEntry> snapshot = new ArrayList<>(entries);
        log.info("Cleaning services for stopped plugin: pluginId={}, count={}", pluginId, snapshot.size());
        for (ServiceEntry e : snapshot) {
            unregisterEntry(e);
        }
    }

    /**
     * 通过服务实例的 ClassLoader 反查所属 pluginId。
     * 找不到时返回 {@link #UNKNOWN_PLUGIN_ID}（仍允许注册，但无自动清理兜底）。
     */
    private String detectPluginId(Service service) {
        if (pluginManager == null) {
            return UNKNOWN_PLUGIN_ID;
        }
        ClassLoader cl = service.getClass().getClassLoader();
        for (PluginWrapper pw : pluginManager.getPlugins()) {
            if (pw.getPluginClassLoader() == cl) {
                return pw.getPluginId();
            }
        }
        // 主应用 ClassLoader 加载的（如 host 自身的 Service）也允许注册
        return UNKNOWN_PLUGIN_ID;
    }

    // ==================== 内部数据结构 ====================

    private static final class ServiceEntry {
        final Class<? extends Service> type;
        final Service service;
        final String name;
        final String pluginId;
        final AtomicBoolean valid = new AtomicBoolean(true);

        ServiceEntry(Class<? extends Service> type, Service service, String name, String pluginId) {
            this.type = type;
            this.service = service;
            this.name = name;
            this.pluginId = pluginId;
        }
    }

    /**
     * {@link ServiceRegistration} 的实现：持有 entry 与 registry 的引用，
     * {@link #unregister()} 时回调 registry 内部清理。
     */
    private static final class RegistrationImpl<S extends Service> implements ServiceRegistration<S> {
        private final ServiceEntry entry;
        private final ServiceRegistryImpl owner;

        RegistrationImpl(ServiceEntry entry, ServiceRegistryImpl owner) {
            this.entry = entry;
            this.owner = owner;
        }

        @Override
        public void unregister() {
            owner.unregisterEntry(entry);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Class<S> serviceType() {
            return (Class<S>) entry.type;
        }

        @Override
        public String name() {
            return entry.name;
        }

        @Override
        public boolean isValid() {
            return entry.valid.get();
        }
    }
}
