package cn.seifly.jharness.plugins.ui.controller;

import cn.seifly.jharness.plugin.framework.redis.ConfigStore;
import cn.seifly.jharness.plugin.framework.redis.RedisKeys;
import cn.seifly.jharness.plugin.framework.redis.RedisShared;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 进化功能状态查询 API 控制器
 *
 * 提供反馈功能和提示词优化功能的状态查询。
 *
 * <p>迁移说明：反馈数据从 Redis 的 {@link RedisKeys#EVOLUTION_FEEDBACK_NAMESPACE} 命名空间读取，
 * 不再注入跨插件 {@code Config} / {@code AgentRuntime}（运行期对象无法序列化共享）。
 * 提示词优化统计依赖运行期 {@code PromptOptimizer}，暂以 TODO 占位。
 */
@RestController
@RequestMapping("/api/feedback")
@CrossOrigin(origins = "${jclaw.gateway.cors-origin:*}", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.OPTIONS})
@Slf4j
public class FeedbackController {

    private final ConfigStore store = RedisShared.store();

    /**
     * 获取进化功能状态
     *
     * @return 包含 feedbackEnabled、promptOptimizationEnabled 的状态信息
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getStatus() {
        Set<String> feedbackKeys = store.keys(RedisKeys.EVOLUTION_FEEDBACK_NAMESPACE);
        boolean feedbackEnabled = !feedbackKeys.isEmpty();

        Map<String, Object> result = new HashMap<>();
        result.put("feedbackEnabled", feedbackEnabled);
        // TODO promptOptimizationEnabled 及 optimizationStats 依赖运行期 PromptOptimizer，
        //      无法通过 Redis 共享，待调度通道实现后接入。
        result.put("promptOptimizationEnabled", false);

        return ResponseEntity.ok(result);
    }
}
