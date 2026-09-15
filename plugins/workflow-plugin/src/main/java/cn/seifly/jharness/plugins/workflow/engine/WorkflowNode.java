package cn.seifly.jharness.plugins.workflow.engine;

import cn.seifly.jharness.plugins.workflow.AgentRole;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;

/**
 * Workflow 节点定义
 * 描述工作流中的一个执行单元
 */
@Getter
@Setter
public class WorkflowNode {

    /**
     * 节点类型枚举
     */
    public enum NodeType {
        /** 单个Agent执行 */
        SINGLE,
        /** 多Agent并行执行（适合独立子任务） */
        PARALLEL,
        /** 多Agent顺序执行（适合有依赖的任务） */
        SEQUENTIAL,
        /** 条件分支（根据条件选择不同路径） */
        CONDITIONAL,
        /** 循环执行直到条件满足 */
        LOOP,
        /** 聚合多个节点的结果 */
        AGGREGATE
    }

    /** 节点唯一标识 */
    private String id;

    /** 节点名称（可选，用于显示） */
    private String name;

    /** 节点类型 */
    private NodeType type;

    /** 参与执行的Agent角色列表 */
    private List<AgentRole> agents;

    /** 依赖的前置节点ID列表 */
    private List<String> dependsOn;

    /** 输入表达式（如 "${node1.result}"） */
    private String inputExpression;

    /** 条件表达式（CONDITIONAL/LOOP 节点使用） */
    private String condition;

    /** 节点特定配置 */
    private Map<String, Object> config;

    /** 最大重试次数 */
    private int maxRetries;

    /** 超时时间（毫秒），0 表示不限制 */
    private long timeoutMs;

    /** 是否需要人类审批才能执行（Human-in-the-Loop） */
    private boolean requireApproval;

    /** 审批描述（人类可读的审批说明，requireApproval=true 时使用） */
    private String approvalDescription;

    /**
     * 多分支路由表（CONDITIONAL 节点使用）
     * key: 条件值（如 "approve"、"reject"、"default"）
     * value: 目标节点 ID（该分支激活时，其他分支的后续节点将被跳过）
     */
    private Map<String, String> branches;

    public WorkflowNode() {
        this.agents = new ArrayList<>();
        this.dependsOn = new ArrayList<>();
        this.config = new HashMap<>();
        this.branches = new HashMap<>();
        this.maxRetries = 0;
        this.timeoutMs = 0;
        this.requireApproval = false;
    }

    public WorkflowNode(String id, NodeType type) {
        this();
        this.id = id;
        this.type = type;
    }

    /**
     * 便捷构造：创建单Agent节点
     */
    public static WorkflowNode single(String id, AgentRole agent) {
        WorkflowNode node = new WorkflowNode(id, NodeType.SINGLE);
        node.agents.add(agent);
        return node;
    }

    /**
     * 便捷构造：创建并行节点
     */
    public static WorkflowNode parallel(String id, List<AgentRole> agents) {
        WorkflowNode node = new WorkflowNode(id, NodeType.PARALLEL);
        node.agents.addAll(agents);
        return node;
    }

    /**
     * 便捷构造：创建顺序节点
     */
    public static WorkflowNode sequential(String id, List<AgentRole> agents) {
        WorkflowNode node = new WorkflowNode(id, NodeType.SEQUENTIAL);
        node.agents.addAll(agents);
        return node;
    }

    /**
     * 便捷构造：创建聚合节点
     */
    public static WorkflowNode aggregate(String id, List<String> dependsOn) {
        WorkflowNode node = new WorkflowNode(id, NodeType.AGGREGATE);
        node.dependsOn.addAll(dependsOn);
        return node;
    }

    /**
     * 添加依赖节点
     */
    public WorkflowNode dependsOn(String... nodeIds) {
        for (String nodeId : nodeIds) {
            if (!dependsOn.contains(nodeId)) {
                dependsOn.add(nodeId);
            }
        }
        return this;
    }

    /**
     * 设置输入表达式
     */
    public WorkflowNode withInput(String expression) {
        this.inputExpression = expression;
        return this;
    }

    /**
     * 设置条件表达式
     */
    public WorkflowNode withCondition(String condition) {
        this.condition = condition;
        return this;
    }

    /**
     * 添加Agent
     */
    public WorkflowNode addAgent(AgentRole agent) {
        agents.add(agent);
        return this;
    }

    /**
     * 检查是否有依赖
     */
    public boolean hasDependencies() {
        return dependsOn != null && !dependsOn.isEmpty();
    }

    /**
     * 添加分支路由（CONDITIONAL 节点使用）
     *
     * @param conditionValue 条件值
     * @param targetNodeId   目标节点 ID
     */
    public WorkflowNode addBranch(String conditionValue, String targetNodeId) {
        this.branches.put(conditionValue, targetNodeId);
        return this;
    }

    /**
     * 标记该节点需要人类审批
     *
     * @param description 审批描述（人类可读的说明）
     */
    public WorkflowNode withApproval(String description) {
        this.requireApproval = true;
        this.approvalDescription = description;
        return this;
    }

    @Override
    public String toString() {
        return "WorkflowNode{" +
                "id='" + id + '\'' +
                ", type=" + type +
                ", agents=" + agents.size() +
                ", dependsOn=" + dependsOn +
                '}';
    }
}
