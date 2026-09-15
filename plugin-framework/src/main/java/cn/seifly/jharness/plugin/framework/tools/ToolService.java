package cn.seifly.jharness.plugin.framework.tools;

import cn.seifly.jharness.plugin.framework.llm.ToolDefinition;
import cn.seifly.jharness.plugin.framework.service.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具服务契约。
 *
 * <p>由 tools-plugin 在 {@code Plugin.start()} 内注册实现，消费方（如 workflow-plugin）
 * 通过 {@link cn.seifly.jharness.plugin.framework.service.ServiceRegistry#get} 取得，
 * 不再编译期依赖 tools-plugin 的具体实现类。
 *
 * <p>契约形态沿用 #1 LlmService 的「等同契约」思路：方法签名与原
 * {@code ToolRegistry} 保持一致，仅把类型提升到 SDK，避免大面积调用点改造。
 *
 * <ul>
 *   <li>{@link #register} / {@link #unregister}：工具的动态注册与注销；
 *       未来各插件实现自己的 Tool 后可通过 {@code services.get(ToolService.class)}
 *       取得本服务并注册自身。</li>
 *   <li>{@link #get} / {@link #hasTool} / {@link #list} / {@link #count}：查询。</li>
 *   <li>{@link #execute}：按名调用工具并返回字符串结果。</li>
 *   <li>{@link #getDefinitions} / {@link #getSummaries}：生成 OpenAI 工具定义 / 人类可读摘要，
 *       供 LLM 上下文拼装使用。</li>
 *   <li>{@link #filter}：按白名单派生一个受限视图，返回另一个 {@link ToolService} 实例，
 *       用于多 Agent 场景下按角色裁剪可用工具。</li>
 * </ul>
 *
 * <p>所有方法契约语义与原 {@code ToolRegistry} 一致；线程安全由实现方保证。
 */
public interface ToolService extends Service {

    /** 注册一个工具。 */
    void register(Tool tool);

    /** 取消注册一个工具。 */
    void unregister(String name);

    /** 根据名称获取工具。 */
    Optional<Tool> get(String name);

    /** 检查工具是否存在。 */
    boolean hasTool(String name);

    /**
     * 执行工具使用给定的参数。
     *
     * @throws Exception 工具执行失败时抛出
     */
    String execute(String name, Map<String, Object> args) throws Exception;

    /** 获取所有已注册工具的名称。 */
    List<String> list();

    /** 获取已注册工具的数量。 */
    int count();

    /** 获取 OpenAI 格式的工具定义。 */
    List<ToolDefinition> getDefinitions();

    /** 获取人类可读的工具摘要。 */
    List<String> getSummaries();

    /** 清除所有已注册工具。 */
    void clear();

    /**
     * 创建一个只包含指定工具名称的受限视图。
     *
     * @param allowedToolNames 白名单；为 null 或空时返回包含全部工具的副本
     * @return 受限的 {@link ToolService} 实例
     */
    ToolService filter(List<String> allowedToolNames);
}
