package cn.seifly.jharness.plugin.framework.tools;

import java.util.Map;

/**
 * 工具接口，由 SDK 共享，供各插件实现具体工具。
 */
public interface Tool {

    String name();

    String description();

    Map<String, Object> parameters();

    String execute(Map<String, Object> args) throws ToolException;
}
