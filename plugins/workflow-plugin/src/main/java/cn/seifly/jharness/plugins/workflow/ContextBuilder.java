package cn.seifly.jharness.plugins.workflow;

import cn.seifly.jharness.plugins.workflow.context.*;
import cn.seifly.jharness.plugin.framework.memory.MemoryService;
import cn.seifly.jharness.plugins.evolution.PromptOptimizer;
import lombok.extern.slf4j.Slf4j;
import cn.seifly.jharness.plugin.framework.llm.Message;
import cn.seifly.jharness.plugin.framework.llm.ToolCall;
import cn.seifly.jharness.plugins.skills.SkillInfo;
import cn.seifly.jharness.plugins.skills.SkillsLoader;
import cn.seifly.jharness.plugin.framework.tools.ToolService;
import cn.seifly.jharness.plugin.framework.StringUtils;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 上下文构建器，用于构建 Agent 运行所需的完整上下文。
 * 
 * 这是 Agent 系统的核心组件之一，负责组装发送给 LLM 的系统提示词和消息上下文。
 * 
 * 核心职责：
 * - 构建系统提示词：包含身份信息、工具说明、技能摘要、记忆上下文
 * - 加载引导文件：从工作空间加载 AGENTS.md、SOUL.md 等自定义配置
 * - 集成技能系统：将已安装技能的摘要添加到系统提示词中
 * - 管理记忆上下文：加载和整合长期记忆内容
 * 
 * 上下文层次结构：
 * 1. 身份信息：Agent 名称、当前时间、运行环境、工作空间路径
 * 2. 引导文件：用户自定义的行为指导和身份定义
 * 3. 工具说明：已注册工具的功能描述和使用方法
 * 4. 技能摘要：已安装技能的简要说明和位置信息
 * 5. 记忆上下文：长期记忆和近期对话摘要
 * 
 * 设计原则：
 * - 渐进式披露：提供摘要而非完整内容，减少 token 消耗
 * - 模块化组装：各部分独立构建，便于扩展和维护
 * - 优先级覆盖：workspace > global > builtin 的技能加载顺序
 */
@Slf4j
public class ContextBuilder {
    
    private static final String SECTION_SEPARATOR = "\n\n---\n\n";
    
    private final String workspace;
    private ToolService tools;
    // #2 重构后 memory 改为 SDK 契约 MemoryService，由 WorkflowRuntime 在构造后注入；
    // 去掉 final 以支持延迟 setMemory。未注入时为 null，各 section 内需 null 防护。
    private MemoryService memory;
    private final SkillsLoader skillsLoader;
    
    private volatile PromptOptimizer promptOptimizer;
    
    private int contextWindow = AgentConstants.DEFAULT_CONTEXT_WINDOW;
    
    private final List<ContextSection> sections = new ArrayList<>();
    
    /**
     * 创建上下文构建器。
     * 
     * 初始化时会自动创建 SkillsLoader 实例；MemoryService 由 WorkflowRuntime 在构造后注入。
     * SkillsLoader 会尝试从多个位置加载技能：
     * - workspace/skills：项目级技能（最高优先级）
     * - 全局技能目录
     * - 内置技能目录
     * 
     * @param workspace 工作空间路径
     */
    public ContextBuilder(String workspace) {
        this.workspace = workspace;
        // #2 重构：memory 不在此处 new，由 WorkflowRuntime 在构造后通过 setMemory 注入
        this.skillsLoader = new SkillsLoader(workspace, null, null);
        initializeSections();
    }
    
    /**
     * 创建带完整配置的上下文构建器。
     * 
     * 允许指定全局和内置技能目录，用于高级配置场景。
     * 
     * @param workspace 工作空间路径
     * @param globalSkills 全局技能目录路径
     * @param builtinSkills 内置技能目录路径
     */
    public ContextBuilder(String workspace, String globalSkills, String builtinSkills) {
        this.workspace = workspace;
        // #2 重构：memory 不在此处 new，由 WorkflowRuntime 在构造后通过 setMemory 注入
        this.skillsLoader = new SkillsLoader(workspace, globalSkills, builtinSkills);
        initializeSections();
    }
    
    /**
     * 初始化内置的 section 列表。
     */
    private void initializeSections() {
        sections.add(new IdentitySection());
        sections.add(new BootstrapSection());
        sections.add(new ToolsSection());
        sections.add(new SkillsSection());
        sections.add(new MemorySection());
    }
    
    /**
     * 添加自定义 section。
     * 
     * @param section 要添加的 section
     */
    public void addSection(ContextSection section) {
        sections.add(section);
    }
    
    /**
     * 设置工具服务用于动态工具摘要生成。
     *
     * @param tools 工具服务实例（来自 SDK {@link ToolService} 契约）
     */
    public void setTools(ToolService tools) {
        this.tools = tools;
    }

    /**
     * 注入记忆服务实例（#2 重构）。
     *
     * <p>memory 不在构造器内创建，而是由 WorkflowRuntime 在构造 ContextBuilder 后，
     * 从 {@link cn.seifly.jharness.plugin.framework.service.ServiceRegistry} 取得
     * 由 memory-plugin 注册的 {@link MemoryService} 实例并通过本方法注入；
     * 之后由 WorkflowRuntime 调用 {@link MemoryService#init} 推入 workspace 路径。
     *
     * @param memory 记忆服务实例，可为 null（缺失时记忆相关 section 输出空）
     */
    public void setMemory(MemoryService memory) {
        this.memory = memory;
    }
    
    /**
     * 设置 Prompt 优化器（可选，用于进化功能）。
     * 
     * 设置后，系统提示词将包含优化后的行为指导。
     * 
     * @param promptOptimizer Prompt 优化器实例
     */
    public void setPromptOptimizer(PromptOptimizer promptOptimizer) {
        this.promptOptimizer = promptOptimizer;
    }
    
    /**
     * 获取 Prompt 优化器。
     * 
     * @return 优化器实例，未设置时返回 null
     */
    public PromptOptimizer getPromptOptimizer() {
        return promptOptimizer;
    }
    
    /**
     * 构建系统提示词（无当前消息上下文版本）。
     * 
     * 使用默认记忆预算，不做相关性过滤。适用于不需要消息感知的场景。
     * 
     * @return 完整的系统提示词字符串
     */
    public String buildSystemPrompt() {
        return buildSystemPrompt(null);
    }
    
    /**
     * 构建系统提示词，支持基于当前消息的记忆相关性检索。
     * 
     * 这是上下文构建的核心方法，按照特定顺序组装各个部分：
     * 1. 身份信息：Agent 的基本身份和当前环境信息
     * 2. 引导文件：用户自定义的行为配置
     * 3. 工具部分：可用工具的简要说明
     * 4. 技能摘要：已安装技能的概述
     * 5. 记忆上下文：根据当前消息和 token 预算智能选取
     * 
     * @param currentMessage 当前用户消息，用于记忆相关性匹配（可为 null）
     * @return 完整的系统提示词字符串
     */
    public String buildSystemPrompt(String currentMessage) {
        SectionContext ctx = new SectionContext(
            currentMessage, workspace, contextWindow,
            tools, promptOptimizer, skillsLoader, memory
        );
        
        List<String> parts = new ArrayList<>();
        for (ContextSection section : sections) {
            String content = section.build(ctx);
            if (StringUtils.isNotBlank(content)) {
                parts.add(content);
            }
        }
        
        return String.join(SECTION_SEPARATOR, parts);
    }
    
    /**
     * 设置上下文窗口大小，用于动态计算记忆 token 预算。
     *
     * @param contextWindow 上下文窗口 token 数
     */
    public void setContextWindow(int contextWindow) {
        this.contextWindow = contextWindow;
    }

    /**
     * 为 LLM 构建消息列表。
     * 
     * 组装完整的消息上下文，包括系统提示词、历史消息和当前用户消息。
     * 
     * @param history 历史消息列表
     * @param summary 之前对话的摘要
     * @param currentMessage 当前用户消息
     * @param channel 当前通道名称
     * @param chatId 当前聊天 ID
     * @return 完整的消息列表
     */
    public List<Message> buildMessages(List<Message> history, String summary, String currentMessage, 
                                        String channel, String chatId) {
        return buildMessages(history, summary, currentMessage, null, channel, chatId);
    }
    
    /**
     * 为 LLM 构建消息列表，支持多模态内容（文本+图片）。
     * 
     * 组装完整的消息上下文，包括系统提示词、历史消息和当前用户消息。
     * 当包含图片时，使用多模态消息格式。
     * 
     * @param history 历史消息列表
     * @param summary 之前对话的摘要
     * @param currentMessage 当前用户消息
     * @param images 图片路径列表（可为 null，可以是相对路径或完整路径）
     * @param channel 当前通道名称
     * @param chatId 当前聊天 ID
     * @return 完整的消息列表
     */
    public List<Message> buildMessages(List<Message> history, String summary, String currentMessage, 
                                        List<String> images, String channel, String chatId) {
        List<Message> messages = new ArrayList<>();
        
        // 构建系统提示词（传入当前消息用于记忆相关性检索）
        String systemPrompt = buildSystemPromptWithSession(currentMessage, channel, chatId, summary);
        
        if (log.isDebugEnabled()) {
            log.debug("System prompt built: total_chars={}, total_lines={}",
                    systemPrompt.length(), systemPrompt.split("\n").length);
        }
        
        // 添加系统消息
        messages.add(Message.system(systemPrompt));
        
        // 添加历史记录（清理可能存在的孤立 tool 消息，防止 LLM API 报错）
        if (history != null && !history.isEmpty()) {
            // 处理历史消息中的图片路径
            List<Message> processedHistory = processHistoryImages(sanitizeHistory(new ArrayList<>(history)));
            messages.addAll(processedHistory);
        }
        
        // 添加当前用户消息（支持多模态）
        if (images != null && !images.isEmpty()) {
            // 将相对路径转换为完整路径
            List<String> fullPaths = resolveImagePaths(images);
            messages.add(Message.user(currentMessage, fullPaths));
        } else {
            messages.add(Message.user(currentMessage));
        }
        
        return messages;
    }
    
    /**
     * 将相对图片路径转换为完整路径。
     * 如果路径已经是完整路径或 data URI，则不做转换。
     */
    private List<String> resolveImagePaths(List<String> images) {
        List<String> resolved = new ArrayList<>();
        for (String imagePath : images) {
            if (imagePath.startsWith("data:") || imagePath.startsWith("/")) {
                // 已经是 data URI 或完整路径
                resolved.add(imagePath);
                log.info("图片路径保持不变: path={}",
                        imagePath.length() > 50 ? imagePath.substring(0, 50) + "..." : imagePath);
            } else {
                // 相对路径，转换为完整路径
                String fullPath = Paths.get(workspace, imagePath).toAbsolutePath().toString();
                resolved.add(fullPath);
                log.info("图片路径转换: relative={}, workspace={}, full_path={}",
                        imagePath, workspace, fullPath);
            }
        }
        return resolved;
    }
    
    /**
     * 处理历史消息中的图片：去除图片数据，只保留文字内容。
     *
     * 图片 Base64 数据体积巨大（1MB 原图 ≈ 35K tokens），若将历史消息中的图片
     * 随每轮对话重复发送，会导致上下文窗口迅速膨胀。
     * 因此历史消息中的图片一律丢弃——模型在当轮已经看过图片，后续轮次无需重复传入。
     */
    private List<Message> processHistoryImages(List<Message> history) {
        List<Message> processed = new ArrayList<>();
        for (Message msg : history) {
            if (msg.hasImages()) {
                // 创建不含图片的副本，保留文字内容和工具调用信息
                Message textOnlyMsg = new Message(msg.getRole(), msg.getContent());
                textOnlyMsg.setToolCalls(msg.getToolCalls());
                textOnlyMsg.setToolCallId(msg.getToolCallId());
                processed.add(textOnlyMsg);
                if (log.isDebugEnabled()) {
                    log.debug("Dropped images from history message to reduce context size: role={}, image_count={}",
                            msg.getRole(), msg.getImages().size());
                }
            } else {
                processed.add(msg);
            }
        }
        return processed;
    }
    
    /**
     * 构建包含会话信息的系统提示词。
     * 
     * @param currentMessage 当前用户消息（用于记忆相关性检索）
     * @param channel 通道名称
     * @param chatId 聊天 ID
     * @param summary 对话摘要
     * @return 完整的系统提示词
     */
    private String buildSystemPromptWithSession(String currentMessage, String channel, String chatId, String summary) {

        StringBuilder systemPrompt = new StringBuilder(buildSystemPrompt(currentMessage));
        
        // 添加当前会话信息
        if (StringUtils.isNotBlank(channel) && StringUtils.isNotBlank(chatId)) {
            systemPrompt.append("\n\n## 当前会话\n通道: ").append(channel)
                       .append("\n聊天 ID: ").append(chatId);
        }
        
        // 添加对话摘要
        if (StringUtils.isNotBlank(summary)) {
            systemPrompt.append("\n\n## 之前对话的摘要\n\n").append(summary);
        }
        
        return systemPrompt.toString();
    }
    
    /**
     * 清理历史消息，确保 tool 消息前面有对应的 assistant(tool_calls) 消息。
     * 
     * LLM API 要求每条 role="tool" 的消息前面必须紧跟一条包含 tool_calls 的
     * role="assistant" 消息。此方法会跳过历史开头处缺少配对 assistant 消息的
     * 孤立 tool 消息，防止发送给 LLM 时报 400 错误。
     * 
     * @param history 原始历史消息列表
     * @return 清理后的历史消息列表
     */
    private List<Message> sanitizeHistory(List<Message> history) {
        if (history.isEmpty()) {
            return history;
        }

        // 找到第一条非孤立 tool 消息的位置
        int startIndex = 0;
        while (startIndex < history.size() && "tool".equals(history.get(startIndex).getRole())) {
            startIndex++;
        }

        List<Message> result = (startIndex == 0)
                ? history
                : new ArrayList<>(history.subList(startIndex, history.size()));

        if (startIndex > 0) {
            log.warn("Skipped orphaned tool messages at history start: skipped_count={}", startIndex);
        }

        // 规范化 tool call ID，避免历史消息中的重复 ID 导致 LLM API 报错
        // （Moonshot/Kimi 等 API 会复用 toolname:sequence 格式 ID，跨会话累积后冲突）
        normalizeHistoryToolCallIds(result);

        return result;
    }

    /**
     * 规范化历史消息中的 tool call ID，并修复孤立的 tool_calls。
     *
     * <p>处理两类问题：
     * <ol>
     *   <li><b>重复 ID</b> — Moonshot/Kimi 等 API 复用 {@code toolname:sequence} 格式 ID，
     *       跨会话累积后冲突，被 API 拒绝。为每个 tool_call 生成全局唯一 ID。</li>
     *   <li><b>孤立 tool_calls</b> — assistant 消息含 {@code tool_calls} 但缺少对应的
     *       tool 结果消息（因执行中断/异常导致未保存）。LLM API 要求 assistant tool_calls
     *       后必须紧跟每个 {@code tool_call_id} 的结果消息，否则报错。</li>
     * </ol>
     *
     * <p>修复策略：对孤立 tool_call 注入占位 tool 结果消息
     * （内容标注为执行中断），紧随 assistant 消息之后。
     *
     * @param messages 历史消息列表（原地修改）
     */
    private void normalizeHistoryToolCallIds(List<Message> messages) {
        // 第一遍：规范化 assistant tool_call 的 ID，建立 old_id -> new_id 映射
        Map<String, String> idMapping = new HashMap<>();
        for (Message msg : messages) {
            if ("assistant".equals(msg.getRole()) && msg.getToolCalls() != null) {
                for (ToolCall tc : msg.getToolCalls()) {
                    String oldId = tc.getId();
                    String newId = "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                    if (oldId != null) {
                        idMapping.put(oldId, newId);
                    }
                    tc.setId(newId);
                }
            }
        }

        // 第二遍：用映射更新 tool 结果消息的 tool_call_id
        for (Message msg : messages) {
            if ("tool".equals(msg.getRole()) && msg.getToolCallId() != null) {
                String oldId = msg.getToolCallId();
                String newId = idMapping.get(oldId);
                if (newId != null) {
                    msg.setToolCallId(newId);
                } else {
                    // 找不到对应的 assistant tool_call，生成新 ID 避免与其他 tool 消息冲突
                    msg.setToolCallId("call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
                }
            }
        }

        // 第三遍：检测并修复孤立的 tool_calls（assistant 有 tool_calls 但缺 tool 结果）
        fixOrphanedToolCalls(messages);
    }

    /**
     * 修复孤立的 tool_calls：为缺少结果消息的 tool_call_id 注入占位 tool 结果。
     *
     * <p>LLM API 要求 assistant 消息的 {@code tool_calls} 后必须紧跟每个
     * {@code tool_call_id} 对应的 tool 结果消息。若因执行中断/异常导致缺失，
     * 注入占位结果以满足 API 约束。
     *
     * @param messages 历史消息列表（原地修改）
     */
    private void fixOrphanedToolCalls(List<Message> messages) {
        // 收集所有已有的 tool 结果消息的 tool_call_id
        java.util.Set<String> answeredIds = new java.util.HashSet<>();
        for (Message msg : messages) {
            if ("tool".equals(msg.getRole()) && msg.getToolCallId() != null) {
                answeredIds.add(msg.getToolCallId());
            }
        }

        // 检查每个 assistant 消息的 tool_calls 是否都有对应结果
        List<Message> toInsert = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (!"assistant".equals(msg.getRole()) || msg.getToolCalls() == null) {
                continue;
            }
            for (ToolCall tc : msg.getToolCalls()) {
                String tcId = tc.getId();
                if (tcId != null && !answeredIds.contains(tcId)) {
                    // 孤立 tool_call：注入占位结果
                    Message placeholder = Message.tool(tcId,
                            "[工具执行中断，结果未保存]");
                    placeholder.setRole("tool");
                    // 标记插入位置（在当前 assistant 消息之后）
                    toInsert.add(placeholder);
                    log.warn("注入孤立 tool_call 占位结果: tool_call_id={}, tool_name={}",
                            tcId, tc.getName());
                }
            }
        }

        if (!toInsert.isEmpty()) {
            // 简化策略：将占位消息追加到列表末尾（LLM API 通常容忍非连续的 tool 结果位置）
            messages.addAll(toInsert);
            log.warn("已注入孤立 tool_call 占位结果: count={}", toInsert.size());
        }
    }
    
    /**
     * 获取技能加载器实例。
     * 
     * 用于与其他组件（如 SkillsTool）共享同一个 SkillsLoader 实例，
     * 确保技能列表视图的一致性。
     * 
     * @return 技能加载器实例
     */
    public SkillsLoader getSkillsLoader() {
        return skillsLoader;
    }

    /**
     * 获取记忆服务实例，供外部组件（如 SessionSummarizer、ProviderManager）访问记忆读写能力。
     *
     * <p>#2 重构后返回类型提升到 SDK {@link MemoryService} 契约；
     * 调用方通过契约方法操作记忆，不再编译期依赖 memory-plugin 实现类。</p>
     *
     * @return 记忆服务实例，未注入时为 null
     */
    public MemoryService getMemoryStore() {
        return memory;
    }
    
    /**
     * 获取已加载技能的信息。
     * 
     * 返回当前已安装技能的统计信息，包括：
     * - total: 技能总数
     * - available: 可用技能数（与 total 相同）
     * - names: 所有技能名称列表
     * 
     * 这些信息用于状态报告和监控目的。
     * 
     * @return 包含技能信息的映射
     */
    public Map<String, Object> getSkillsInfo() {
        List<SkillInfo> allSkills = skillsLoader.listSkills();
        List<String> skillNames = allSkills.stream()
                .map(SkillInfo::getName)
                .toList();
        
        Map<String, Object> info = new HashMap<>();
        info.put("total", allSkills.size());
        info.put("available", allSkills.size());
        info.put("names", skillNames);
        return info;
    }
}
