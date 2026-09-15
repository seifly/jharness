package cn.seifly.jharness.plugin.framework.service;

/**
 * 插件服务标记接口。
 *
 * <p>所有由插件实现并通过 {@link ServiceRegistry} 暴露给其它插件的「服务契约」
 * 都需要继承此接口。它本身不携带任何方法，仅作为类型边界与编译期约束：
 * <ul>
 *   <li>让 {@link ServiceRegistry} 的注册/查询 API 拥有统一的类型参数上界；</li>
 *   <li>避免把任意接口误注册为「服务」。</li>
 * </ul>
 *
 * <p>设计动机：插件之间在 PF4J 下使用独立的 ClassLoader，无法直接相互引用实现类。
 * 将「能力」抽象为 {@code Service} 接口并放在 SDK（plugin-framework）中由所有插件共享，
 * Plugin 在 {@code start()} 内构造实现并通过 {@link ServiceRegistry#register} 注册，
 * 消费方通过 {@link ServiceRegistry#get} 获取，从而解耦插件之间的直接依赖：
 * <pre>
 *              ServiceRegistry
 *             /      |      \
 *            /       |       \
 *         LLM       Tool     Memory
 *          ^          ^         ^
 *          |          |         |
 *       Plugin A   Plugin B   Plugin C
 * </pre>
 * Plugin 不互相依赖，Plugin 依赖 Service。
 */
public interface Service {
}
