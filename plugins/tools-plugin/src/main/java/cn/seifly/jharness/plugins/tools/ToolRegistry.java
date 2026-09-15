package cn.seifly.jharness.plugins.tools;

import cn.seifly.jharness.plugin.framework.tools.Tool;
import cn.seifly.jharness.plugin.framework.tools.ToolService;
import cn.seifly.jharness.plugin.framework.llm.ToolDefinition;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册表 - 用于管理和执行各种工具
 *
 * 这是JClaw工具系统的核心管理组件，负责：
 *
 * 核心功能：
 * - 工具注册与发现：维护系统中所有可用工具的注册信息
 * - 工具执行：提供统一的工具调用接口
 * - 生命周期管理：支持工具的动态注册和注销
 * - 元数据管理：生成工具定义和摘要信息供LLM使用
 *
 * 设计特点：
 * - 线程安全：使用ConcurrentHashMap确保并发访问安全
 * - 性能监控：记录工具执行时间和结果统计
 * - 错误处理：完善的异常处理和日志记录
 * - 标准化接口：遵循OpenAI工具定义格式
 *
 */
@Slf4j
public class ToolRegistry implements ToolService {

    private final Map<String, Tool> tools;

    public ToolRegistry() {
        this.tools = new ConcurrentHashMap<>();
    }

    /**
     * 注册一个工具
     */
    public void register(Tool tool) {
        tools.put(tool.name(), tool);
        if (log.isDebugEnabled()) {
            log.debug("Registered tool: {}", tool.name());
        }
    }

    /**
     * 取消注册一个工具
     */
    public void unregister(String name) {
        tools.remove(name);
        if (log.isDebugEnabled()) {
            log.debug("Unregistered tool: {}", name);
        }
    }

    /**
     * 根据名称获取工具
     */
    public Optional<Tool> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * 检查工具是否存在
     */
    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    /**
     * 执行工具使用给定的参数
     */
    public String execute(String name, Map<String, Object> args) throws Exception {
        Tool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("Tool not found: " + name);
        }

        long start = System.currentTimeMillis();
        try {
            String result = tool.execute(args);
            long duration = System.currentTimeMillis() - start;
            log.info("Tool executed: tool={}, duration_ms={}, result_length={}",
                    name, duration, result != null ? result.length() : 0);
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("Tool execution failed: tool={}, duration_ms={}, error={}",
                    name, duration, e.getMessage());
            throw e;
        }
    }

    /**
     * 获取所有已注册工具的名称
     */
    public List<String> list() {
        return new ArrayList<>(tools.keySet());
    }

    /**
     * 获取已注册工具的数量
     */
    public int count() {
        return tools.size();
    }

    /**
     * 获取OpenAI格式的工具定义
     */
    public List<ToolDefinition> getDefinitions() {
        List<ToolDefinition> definitions = new ArrayList<>();
        for (Tool tool : tools.values()) {
            definitions.add(new ToolDefinition(tool.name(), tool.description(), tool.parameters()));
        }
        return definitions;
    }

    /**
     * 获取人类可读的工具摘要
     */
    public List<String> getSummaries() {
        List<String> summaries = new ArrayList<>();
        for (Tool tool : tools.values()) {
            summaries.add("- `" + tool.name() + "` - " + tool.description());
        }
        return summaries;
    }

    /**
     * 清除所有已注册工具
     */
    public void clear() {
        tools.clear();
        if (log.isDebugEnabled()) {
            log.debug("All tools cleared");
        }
    }

    /**
     * 创建一个只包含指定工具名称的受限工具注册表。
     */
    @Override
    public ToolService filter(List<String> allowedToolNames) {
        if (allowedToolNames == null || allowedToolNames.isEmpty()) {
            ToolRegistry copy = new ToolRegistry();
            tools.values().forEach(copy::register);
            return copy;
        }

        ToolRegistry restricted = new ToolRegistry();
        for (String name : allowedToolNames) {
            Tool tool = tools.get(name);
            if (tool != null) {
                restricted.register(tool);
            } else {
                log.warn("allowedTools 中指定的工具未注册，已忽略: {}", name);
            }
        }
        return restricted;
    }
}
