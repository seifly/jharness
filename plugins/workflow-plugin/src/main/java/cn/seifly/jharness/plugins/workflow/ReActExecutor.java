package cn.seifly.jharness.plugins.workflow;

import cn.seifly.jharness.plugins.evolution.FeedbackManager;
import lombok.extern.slf4j.Slf4j;
import cn.seifly.jharness.plugin.framework.llm.*;
import cn.seifly.jharness.plugin.framework.tools.StreamAwareTool;
import cn.seifly.jharness.plugin.framework.tools.ToolContextAware;
import cn.seifly.jharness.plugin.framework.tools.ToolService;
import cn.seifly.jharness.plugin.framework.tools.TokenUsageService;
import cn.seifly.jharness.plugins.sessions.SessionManager;
import cn.seifly.jharness.plugins.sessions.ToolCallRecord;
import cn.seifly.jharness.plugin.framework.StringUtils;
import cn.seifly.jharness.plugins.workflow.XmlToolCallParser;

import java.util.*;

import static cn.seifly.jharness.plugins.workflow.AgentConstants.*;

/**
 * LLM 执行器，负责与 LLM 交互和工具调用迭代。
 * 
 * 核心功能：
 * - 管理 LLM 的请求响应循环
 * - 处理工具调用的执行和结果反馈
 * - 支持流式和非流式两种模式
 * - 控制迭代次数避免无限循环
 */
@Slf4j
public class ReActExecutor {
    
    private static final int MAX_EMPTY_RESPONSE_RETRIES = 2;
    private static final String EMPTY_RESPONSE_FALLBACK = "抱歉，我暂时无法处理这个请求。请稍后重试，或尝试换一种方式描述你的需求。";
    
    private final LlmService provider;       // LLM 服务提供者
    private final ToolService tools;          // 工具服务（SDK 契约，由 tools-plugin 注册）
    private final SessionManager sessions;    // 会话管理器
    private final String model;               // 使用的模型名称
    private final String providerName;        // 提供商名称（如 dashscope、openai）
    private final int maxIterations;          // 最大迭代次数

    /** Token 消耗服务（可选，注入后自动记录每次 LLM 调用的 token 数据） */
    private volatile TokenUsageService tokenUsageService;

    /** 反馈管理器（可选，用于进化模块） */
    private volatile FeedbackManager feedbackManager;
    
    /** 当前会话标识（用于反馈记录） */
    private String currentSessionKey;
    
    /** 当前流式回调（用于传递给子代理和协同工具） */
    private volatile LlmService.EnhancedStreamCallback currentEnhancedCallback;
    
    /** 中断标志位，用于外部请求中断当前执行循环 */
    private volatile boolean aborted = false;
    
    /** 运行状态标志位，标记当前是否有执行循环正在运行 */
    private volatile boolean running = false;
    
    /**
     * LLM 调用器函数式接口，用于抽象不同执行模式的 LLM 调用行为。
     */
    @FunctionalInterface
    private interface LLMCaller {
        LLMResponse call(List<Message> messages) throws Exception;
    }

    /**
     * 工具执行器函数式接口，用于抽象不同执行模式的工具调用行为。
     */
    @FunctionalInterface
    private interface ToolExecutor {
        void execute(List<Message> messages, List<ToolCall> toolCalls, String sessionKey, int iteration) throws Exception;
    }
    
    public ReActExecutor(LlmService provider, ToolService tools, SessionManager sessions,
                         String model, String providerName, int maxIterations) {
        this.provider = provider;
        this.tools = tools;
        this.sessions = sessions;
        this.model = model;
        this.providerName = providerName != null ? providerName : "unknown";
        this.maxIterations = maxIterations;
    }

    /**
     * 设置 Token 消耗服务（可选，注入后自动记录每次 LLM 调用的 token 数据）。
     *
     * @param tokenUsageService Token 消耗服务实例（SDK 契约）
     */
    public void setTokenUsageService(TokenUsageService tokenUsageService) {
        this.tokenUsageService = tokenUsageService;
    }

    /**
     * 设置反馈管理器（可选，用于进化模块）。
     *
     * @param feedbackManager 反馈管理器实例
     */
    public void setFeedbackManager(FeedbackManager feedbackManager) {
        this.feedbackManager = feedbackManager;
    }
    
    /**
     * 请求中断当前执行循环。
     * 设置中断标志位后，executeLoop 会在下一次迭代开始时检测到并提前退出。
     */
    public void abort() {
        this.aborted = true;
    }

    /**
     * 检查当前执行是否已被中断。
     *
     * @return 如果已被中断返回 true
     */
    public boolean isAborted() {
        return aborted;
    }

    /**
     * 检查当前是否有执行循环正在运行。
     *
     * @return 如果正在运行返回 true
     */
    public boolean isRunning() {
        return running;
    }
    
    /**
     * 执行 LLM 迭代循环。
     * 
     * 处理流程：
     * 1. 调用 LLM 获取响应
     * 2. 如果没有工具调用请求，返回文本响应
     * 3. 如果有工具调用，执行工具并将结果追加到消息历史
     * 4. 重复上述流程直到达到最大迭代次数或获得最终响应
     * 
     * @param messages 完整的对话历史
     * @param sessionKey 会话标识符
     * @return LLM 的最终回答内容
     * @throws Exception 调用 LLM 或执行工具时的异常
     */
    public String execute(List<Message> messages, String sessionKey) throws Exception {
        this.currentSessionKey = sessionKey;
        this.aborted = false;
        this.running = true;
        try {
            return executeLoop(messages, sessionKey, null, 
                msgs -> callLLM(msgs),
                (msgs, toolCalls, sk, iter) -> executeToolCalls(msgs, toolCalls, sk, iter)
            );
        } finally {
            this.running = false;
        }
    }
    
    /**
     * 执行 LLM 流式迭代循环。
     * 
     * 与普通迭代循环相同，但支持流式输出响应内容。
     * 适用于需要实时展示 LLM 响应的场景。
     * 
     * @param messages 完整的对话历史
     * @param sessionKey 会话标识符
     * @param callback 流式内容回调函数
     * @return LLM 的最终回答内容
     * @throws Exception 调用 LLM 或执行工具时的异常
     */
    public String executeStream(List<Message> messages, String sessionKey, 
                               LlmService.StreamCallback callback) throws Exception {
        this.currentSessionKey = sessionKey;
        this.aborted = false;
        this.running = true;
        this.currentEnhancedCallback = LlmService.EnhancedStreamCallback.wrap(callback);
        
        try {
            return executeLoop(messages, sessionKey, callback,
                msgs -> callLLMStream(msgs, callback),
                (msgs, toolCalls, sk, iter) -> executeToolCallsWithStream(msgs, toolCalls, sk, iter)
            );
        } finally {
            this.running = false;
            this.currentEnhancedCallback = null;
        }
    }
    
    /**
     * 统一的 LLM 迭代循环实现。
     * 
     * 通过函数式接口参数抽象不同执行模式的差异点，实现 execute() 和 executeStream() 的逻辑复用。
     * 
     * @param messages 完整的对话历史
     * @param sessionKey 会话标识符
     * @param streamCallback 流式回调（可选，仅流式模式需要）
     * @param llmCaller LLM 调用器
     * @param toolExecutor 工具执行器
     * @return LLM 的最终回答内容
     * @throws Exception 调用 LLM 或执行工具时的异常
     */
    private String executeLoop(List<Message> messages, String sessionKey, 
                              LlmService.StreamCallback streamCallback,
                              LLMCaller llmCaller, ToolExecutor toolExecutor) throws Exception {
        int iteration = 0;
        String finalContent = null;
        int emptyRetries = 0;
        int totalAttempts = 0;
        int maxTotalAttempts = maxIterations + MAX_EMPTY_RESPONSE_RETRIES;
        
        while (iteration < maxIterations) {
            // 检查中断标志
            if (aborted) {
                log.info("LLM execution aborted by user: iteration={}, sessionKey={}",
                        iteration, sessionKey != null ? sessionKey : "unknown");
                String abortMessage = "⚠️ 任务已被用户中断。";
                if (streamCallback != null) {
                    streamCallback.onChunk(abortMessage);
                }
                finalContent = abortMessage;
                break;
            }
            totalAttempts++;
            if (totalAttempts > maxTotalAttempts) {
                log.error("LLM exceeded max total attempts (iterations + empty retries): totalAttempts={}, maxTotalAttempts={}, iterations={}, emptyRetries={}",
                        totalAttempts, maxTotalAttempts, iteration, emptyRetries);
                finalContent = EMPTY_RESPONSE_FALLBACK;
                if (streamCallback != null) {
                    streamCallback.onChunk(finalContent);
                }
                break;
            }
            
            iteration++;
            if (log.isDebugEnabled()) {
                log.debug("LLM iteration: iteration={}, max={}", iteration, maxIterations);
            }
            
            LLMResponse response = llmCaller.call(messages);
            
            // 🔧 Fallback: 如果模型不支持 function calling，尝试从 XML 格式中解析工具调用
            if (!response.hasToolCalls() && response.getContent() != null) {
                List<ToolCall> xmlToolCalls = XmlToolCallParser.parseXmlToolCalls(response.getContent());
                if (!xmlToolCalls.isEmpty()) {
                    log.info("🔧 Parsed XML tool calls from content: count={}, tools={}",
                            xmlToolCalls.size(), xmlToolCalls.stream().map(ToolCall::getName).toList());
                    response.setToolCalls(xmlToolCalls);
                    response.setContent("");
                }
            }
            
            // 没有工具调用，返回最终响应
            if (!response.hasToolCalls()) {
                finalContent = response.getContent();
                
                // 空响应保护：如果内容为空且未超过重试次数，则重试
                if (isEmptyContent(finalContent) && emptyRetries < MAX_EMPTY_RESPONSE_RETRIES) {
                    emptyRetries++;
                    log.warn("LLM returned empty response, retrying: iteration={}, retry={}, max_retries={}",
                            iteration, emptyRetries, MAX_EMPTY_RESPONSE_RETRIES);
                    // 空响应重试不计入工具迭代次数，但受 totalAttempts 保护
                    iteration--;
                    continue;
                }
                
                // 重试耗尽仍为空，使用兜底提示
                if (isEmptyContent(finalContent)) {
                    log.warn("LLM returned empty response after retries, using fallback: iteration={}, retries_exhausted={}",
                            iteration, emptyRetries);
                    finalContent = EMPTY_RESPONSE_FALLBACK;
                    if (streamCallback != null) {
                        streamCallback.onChunk(finalContent);
                    }
                }
                
                log.info("LLM response without tool calls: iteration={}, content_chars={}",
                        iteration, finalContent.length());
                break;
            }
            
            // 有工具调用，重置空响应重试计数
            emptyRetries = 0;
            
            // 有工具调用，执行工具并继续迭代
            logToolCalls(response.getToolCalls(), iteration);
            // 规范化 tool call ID，避免跨迭代 ID 冲突
            // （Moonshot/Kimi 等 API 会复用 toolname:sequence 格式的 ID，
            //   导致后续请求中存在重复 ID，被 API 拒绝）
            normalizeToolCallIds(response.getToolCalls());
            addAssistantMessage(messages, response, sessionKey);
            toolExecutor.execute(messages, response.getToolCalls(), sessionKey, iteration);
            // 每轮工具调用后保存一次，防止多轮迭代中途崩溃丢失进度
            sessions.save(sessions.getOrCreate(sessionKey));
        }
        
        if (finalContent == null) {
            log.warn("LLM iteration limit reached without final response: maxIterations={}, totalAttempts={}",
                    maxIterations, totalAttempts);
            finalContent = EMPTY_RESPONSE_FALLBACK;
            if (streamCallback != null) {
                streamCallback.onChunk(finalContent);
            }
        }
        
        return finalContent;
    }
        
    /**
     * 获取当前的增强流式回调（用于子代理和协同工具）。
     * 
     * @return 当前的增强流式回调，如果不在流式模式则返回 null
     */
    public LlmService.EnhancedStreamCallback getEnhancedCallback() {
        return currentEnhancedCallback;
    }
    
    /**
     * 调用 LLM 进行对话。
     * 
     * @param messages 对话消息历史
     * @return LLM 响应
     * @throws Exception 调用失败时抛出异常
     */
    private LLMResponse callLLM(List<Message> messages) throws Exception {
        List<ToolDefinition> toolDefs = tools.getDefinitions();
        Map<String, Object> options = buildLLMOptions();
        LLMResponse response = provider.chat(messages, toolDefs, model, options);
        recordTokenUsage(response);
        return response;
    }
    
    /**
     * 调用 LLM 进行流式对话。
     * 
     * @param messages 对话消息历史
     * @param callback 流式内容回调函数
     * @return LLM 响应
     * @throws Exception 调用失败时抛出异常
     */
    private LLMResponse callLLMStream(List<Message> messages, 
                                      LlmService.StreamCallback callback) throws Exception {
        List<ToolDefinition> toolDefs = tools.getDefinitions();
        Map<String, Object> options = buildLLMOptions();
        LLMResponse response = provider.chatStream(messages, toolDefs, model, options, callback);
        recordTokenUsage(response);
        return response;
    }

    /**
     * 将本次 LLM 调用的 token 消耗记录到 TokenUsageService。
     * 仅在 tokenUsageService 已注入且响应包含 usage 信息时执行。
     *
     * @param response LLM 响应
     */
    private void recordTokenUsage(LLMResponse response) {
        if (tokenUsageService == null || response == null || response.getUsage() == null) {
            return;
        }
        LLMResponse.UsageInfo usage = response.getUsage();
        tokenUsageService.record(providerName, model,
                usage.getPromptTokens(), usage.getCompletionTokens());
    }
    
    /**
     * 构建 LLM 调用选项。
     * 
     * @return 包含 max_tokens 和 temperature 的选项映射
     */
    private Map<String, Object> buildLLMOptions() {
        Map<String, Object> options = new HashMap<>();
        options.put("max_tokens", DEFAULT_MAX_TOKENS);
        options.put("temperature", DEFAULT_TEMPERATURE);
        return options;
    }
    
    /**
     * 记录工具调用信息。
     * 
     * @param toolCalls 工具调用列表
     * @param iteration 当前迭代次数
     */
    private void logToolCalls(List<ToolCall> toolCalls, int iteration) {
        List<String> toolNames = toolCalls.stream()
                .map(ToolCall::getName)
                .toList();

        log.info("LLM requested tool calls: tools={}, count={}, iteration={}",
                toolNames, toolNames.size(), iteration);
    }

    /**
     * 规范化 tool call ID，确保整个会话内 ID 唯一。
     *
     * <p>部分 LLM API（如 Moonshot/Kimi）生成的 tool call ID 格式为
     * {@code toolname:sequence}（如 {@code skills:1}），且会在不同迭代中复用相同 ID。
     * 当多轮工具调用的消息累积到同一请求时，会触发
     * {@code "tool call id xxx is duplicated"} 错误。
     *
     * <p>此方法直接修改 {@code response.getToolCalls()} 中的 ID，
     * 确保 {@code addAssistantMessage} 和 {@code executeToolCallsWithStream}
     * 使用同一组唯一 ID，保持 assistant 消息的 {@code tool_calls[].id}
     * 与 tool 结果消息的 {@code tool_call_id} 一致。
     *
     * @param toolCalls LLM 响应中的工具调用列表（原地修改）
     */
    private void normalizeToolCallIds(List<ToolCall> toolCalls) {
        for (ToolCall tc : toolCalls) {
            String originalId = tc.getId();
            String uniqueId = "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            if (originalId == null || originalId.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug("Tool call ID was null/empty, assigned new: tool={}, new_id={}",
                            tc.getName(), uniqueId);
                }
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("Normalizing tool call ID: tool={}, original_id={}, new_id={}",
                            tc.getName(), originalId, uniqueId);
                }
            }
            tc.setId(uniqueId);
        }
    }
    
    /**
     * 添加助手消息到对话历史。
     * 
     * 将 LLM 的响应（包括工具调用信息）添加到消息列表和会话存储中。
     * 
     * @param messages 对话消息列表
     * @param response LLM 响应
     * @param sessionKey 会话标识符
     */
    private void addAssistantMessage(List<Message> messages, LLMResponse response, String sessionKey) {
        Message assistantMsg = Message.assistant(response.getContent());
        
        // 复制工具调用信息
        List<ToolCall> processedToolCalls = response.getToolCalls().stream()
                .map(tc -> {
                    ToolCall processed = new ToolCall(tc.getId(), tc.getName(), tc.getArguments());
                    processed.setType(tc.getType());
                    return processed;
                })
                .toList();
        
        assistantMsg.setToolCalls(processedToolCalls);
        messages.add(assistantMsg);
        sessions.addFullMessage(sessionKey, assistantMsg);
    }
    
    /**
     * 执行所有工具调用。
     * 
     * 遍历工具调用列表，依次执行每个工具并将结果添加到对话历史中。
     * 
     * @param messages 对话消息列表
     * @param toolCalls 工具调用列表
     * @param sessionKey 会话标识符
     * @param iteration 当前迭代次数
     */
    private void executeToolCalls(List<Message> messages, List<ToolCall> toolCalls, 
                                  String sessionKey, int iteration) {
        for (ToolCall toolCall : toolCalls) {
            // 记录工具调用日志
            String argsPreview = StringUtils.truncate(
                    toolCall.getArguments() != null ? toolCall.getArguments().toString() : "", 
                    200
            );
            log.info("Tool call: tool={}, iteration={}, args_preview={}",
                    toolCall.getName(), iteration, argsPreview);
            
            // 执行工具并保存结果
            String result = executeToolCall(toolCall, sessionKey);
            Message toolResultMsg = Message.tool(toolCall.getId(), result);
            messages.add(toolResultMsg);
            sessions.addFullMessage(sessionKey, toolResultMsg);
        }
    }
    
    /**
     * 执行所有工具调用（流式版本）。
     * 
     * 与普通版本类似，但通过增强回调输出工具调用的过程信息。
     * 
     * @param messages 对话消息列表
     * @param toolCalls 工具调用列表
     * @param sessionKey 会话标识符
     * @param iteration 当前迭代次数
     */
    private void executeToolCallsWithStream(List<Message> messages, List<ToolCall> toolCalls, 
                                            String sessionKey, int iteration) {
        for (ToolCall toolCall : toolCalls) {
            String toolName = toolCall.getName();
            Map<String, Object> args = toolCall.getArguments();
            
            // 记录工具调用日志
            String argsPreview = StringUtils.truncate(
                    args != null ? args.toString() : "", 
                    200
            );
            log.info("Tool call: tool={}, iteration={}, args_preview={}",
                    toolName, iteration, argsPreview);
            
            // 通过增强回调输出工具调用开始事件
            if (currentEnhancedCallback != null) {
                currentEnhancedCallback.onEvent(StreamEvent.toolStart(toolName, args));
            }
            
            // 执行工具
            String result = executeToolCallWithStream(toolCall, sessionKey);
            boolean success = result != null && !result.startsWith("Error:");
            
            // 通过增强回调输出工具调用结束事件
            if (currentEnhancedCallback != null) {
                currentEnhancedCallback.onEvent(StreamEvent.toolEnd(toolName, result, success));
            }

            // 持久化工具调用记录（摘要信息），用于历史会话回放时重建工具调用卡片
            // 用 session 里实际存储的消息数量 - 1 作为触发本次工具调用的 assistant 消息的绝对位置索引。
            // 注意：不能用 messages.size()-1，因为 messages 是发给 LLM 的上下文（可能被截断），
            // 与 session 里存储的完整历史长度不同。
            String argsSummary = ToolCallRecord.truncate(args != null ? args.toString() : "", 500);
            String resultSummary = ToolCallRecord.truncate(result, 500);
            int messageIndex = sessions.getHistory(sessionKey).size() - 1;
            ToolCallRecord record = new ToolCallRecord(toolName, argsSummary, resultSummary, success, messageIndex);
            sessions.addToolCallRecord(sessionKey, record);

            // 保存结果
            Message toolResultMsg = Message.tool(toolCall.getId(), result);
            messages.add(toolResultMsg);
            sessions.addFullMessage(sessionKey, toolResultMsg);
        }
    }
    
    /**
     * 执行单个工具调用。
     * 
     * @param toolCall 工具调用信息
     * @param sessionKey 会话标识符
     * @return 工具执行结果，如果执行失败返回错误信息
     */
    private String executeToolCall(ToolCall toolCall, String sessionKey) {
        String toolName = toolCall.getName();
        boolean success = false;
        String result;
        
        try {
            // 设置工具上下文（从 sessionKey 解析 channel 和 chatId）
            setToolContext(toolName, sessionKey, null);
            result = tools.execute(toolName, toolCall.getArguments());
            // 工具执行成功（没有以 "Error:" 开头）
            success = result != null && !result.startsWith("Error:");
        } catch (Exception e) {
            result = "Error: " + e.getMessage();
        }
        
        // 记录工具执行结果到反馈管理器
        if (feedbackManager != null && currentSessionKey != null) {
            feedbackManager.recordToolResult(currentSessionKey, toolName, success);
        }
        
        return result;
    }
    
    /**
     * 执行单个工具调用（流式版本）。
     * 
     * 与普通版本类似，但会将流式回调传递给支持流式输出的工具（如 SpawnTool、CollaborateTool）。
     * 
     * @param toolCall 工具调用信息
     * @param sessionKey 会话标识符
     * @return 工具执行结果，如果执行失败返回错误信息
     */
    private String executeToolCallWithStream(ToolCall toolCall, String sessionKey) {
        String toolName = toolCall.getName();
        boolean success = false;
        String result;
        
        try {
            // 设置工具上下文，并传递流式回调
            setToolContext(toolName, sessionKey, currentEnhancedCallback);
            result = tools.execute(toolName, toolCall.getArguments());
            // 工具执行成功（没有以 "Error:" 开头）
            success = result != null && !result.startsWith("Error:");
        } catch (Exception e) {
            result = "Error: " + e.getMessage();
        }
        
        // 记录工具执行结果到反馈管理器
        if (feedbackManager != null && currentSessionKey != null) {
            feedbackManager.recordToolResult(currentSessionKey, toolName, success);
        }
        
        return result;
    }
    
    /**
     * 设置工具的上下文信息（channel、chatId 和流式回调）。
     * 
     * 对于需要上下文感知的工具（如 SpawnTool、CollaborateTool、MessageTool、CronTool），
     * 在执行前设置当前的通道、聊天 ID 和流式回调。
     * 
     * @param toolName 工具名称
     * @param sessionKey 会话标识符（格式：channel:chatId）
     * @param streamCallback 流式回调（用于子代理和协同工具，可为 null）
     */
    private void setToolContext(String toolName, String sessionKey, 
                               LlmService.EnhancedStreamCallback streamCallback) {
        if (sessionKey == null || !sessionKey.contains(":")) {
            return;
        }
        
        String[] parts = sessionKey.split(":", 2);
        String channel = parts[0];
        String chatId = parts[1];
        
        // 获取工具实例并设置上下文
        tools.get(toolName).ifPresent(tool -> {
            if (tool instanceof ToolContextAware contextAware) {
                contextAware.setChannelContext(channel, chatId);
            }
            if (tool instanceof StreamAwareTool streamAware) {
                streamAware.setStreamCallback(streamCallback);
            }
        });
    }
    
    /**
     * 判断 LLM 响应内容是否为空。
     * 
     * @param content 响应内容
     * @return 内容为 null 或空白字符串时返回 true
     */
    private boolean isEmptyContent(String content) {
        return content == null || content.trim().isEmpty();
    }
}
