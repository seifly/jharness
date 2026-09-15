package cn.seifly.jharness.plugins.workflow.strategy;

import cn.seifly.jharness.plugins.workflow.*;
import cn.seifly.jharness.plugins.workflow.engine.WorkflowDefinition;
import cn.seifly.jharness.plugins.workflow.engine.WorkflowEngine;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 通用 Workflow 策略
 * 基于 DSL 定义的工作流执行，支持 LLM 动态生成
 */
@Slf4j
public class WorkflowStrategy implements CollaborationStrategy {

    /** Workflow 执行引擎 */
    private final WorkflowEngine engine;

    /** 执行上下文 */
    private final ExecutionContext executionContext;

    public WorkflowStrategy(ExecutionContext executionContext, CollaborationExecutorPool executorPool) {
        this.executionContext = executionContext;
        this.engine = new WorkflowEngine(executorPool);
    }

    @Override
    public String execute(SharedContext context, List<RoleAgent> agents, CollaborationConfig config) {
        WorkflowDefinition workflow = config.getWorkflow();

        if (workflow == null) {
            return "未提供 Workflow 定义";
        }

        // 验证工作流
        WorkflowDefinition.ValidationResult validation = workflow.validate();
        if (!validation.isValid()) {
            log.error("Workflow 定义无效: errors={}", validation.getErrors().toString());
            return "Workflow 定义无效: " + String.join("; ", validation.getErrors());
        }

        log.info("开始执行 Workflow 策略: workflowName={}, nodeCount={}",
                workflow.getName() != null ? workflow.getName() : "unnamed",
                workflow.getNodes().size());

        try {
            // 执行工作流
            String result = engine.execute(workflow, context, executionContext);

            context.setFinalConclusion(result);

            log.info("Workflow 执行完成: resultLength={}", result.length());

            return result;

        } catch (Exception e) {
            log.error("Workflow 执行失败: error={}", e.getMessage(), e);
            return "Workflow 执行失败: " + e.getMessage();
        }
    }

    @Override
    public boolean shouldTerminate(SharedContext context, CollaborationConfig config) {
        // Workflow 由引擎控制终止
        return false;
    }

    @Override
    public String getName() {
        return "Workflow";
    }

    @Override
    public String getDescription() {
        return "通用 Workflow 策略：基于 DSL 定义的工作流执行，支持并行、顺序、条件、循环等节点类型";
    }
}
