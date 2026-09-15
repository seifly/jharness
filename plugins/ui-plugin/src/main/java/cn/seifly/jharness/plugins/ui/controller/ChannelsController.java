package cn.seifly.jharness.plugins.ui.controller;

import cn.seifly.jharness.plugin.framework.config.ChannelsConfig;
import cn.seifly.jharness.plugin.framework.redis.ConfigStore;
import cn.seifly.jharness.plugin.framework.redis.RedisKeys;
import cn.seifly.jharness.plugin.framework.redis.RedisShared;
import cn.seifly.jharness.plugin.framework.service.ChannelService;
import cn.seifly.jharness.plugin.framework.service.ServiceRegistry;
import cn.seifly.jharness.plugins.ui.WebUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static cn.seifly.jharness.plugins.ui.WebUtils.MAPPER;

/**
 * 通道管理 API 控制器
 *
 * 提供通道配置的查询和更新功能，以及微信扫码登录状态、通道动态启停。
 *
 * <p>通道配置从 Redis 的 {@link RedisKeys#NOTIFICATIONS_CHANNELS} 读写，
 * 运行期通道操作（启停、微信登录状态）通过 {@link ChannelService} 委托给
 * channel-plugin 注册的实现。
 */
@RestController
@RequestMapping("/api/channels")
@CrossOrigin(origins = "${jclaw.gateway.cors-origin:*}", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.PUT, RequestMethod.POST, RequestMethod.OPTIONS})
@Slf4j
public class ChannelsController {

    /** 已知通道名称（原 ChannelsController 中遍历的顺序） */
    private static final List<String> CHANNEL_NAMES = Arrays.asList(
            "telegram", "discord", "whatsapp", "wechat", "feishu",
            "dingtalk", "qq", "maixcam", "wecom"
    );

    /** 每个通道中需要掩码展示的敏感字段 */
    private static final Map<String, List<String>> SECRET_FIELDS = new HashMap<>();

    static {
        SECRET_FIELDS.put("telegram", List.of("token"));
        SECRET_FIELDS.put("discord", List.of("token"));
        SECRET_FIELDS.put("wechat", List.of("botToken"));
        SECRET_FIELDS.put("feishu", List.of("appSecret", "encryptKey", "verificationToken"));
        SECRET_FIELDS.put("dingtalk", List.of("clientSecret"));
        SECRET_FIELDS.put("qq", List.of("appSecret"));
        SECRET_FIELDS.put("wecom", List.of("secret"));
    }

    private final ConfigStore store = RedisShared.store();

    @Autowired(required = false)
    private ServiceRegistry services;

    /**
     * 获取 ChannelService（若 channel-plugin 已注册则返回，否则 empty）。
     */
    private Optional<ChannelService> channelService() {
        if (services == null) {
            return Optional.empty();
        }
        return services.get(ChannelService.class);
    }

    /**
     * 获取所有通道的名称及启用状态列表
     *
     * @return 通道列表
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getChannels() {
        ChannelsConfig channels = getChannelsMap();
        Map<String, Object> runtimeStatus = channelService()
                .map(ChannelService::getChannelStatus)
                .orElse(Collections.emptyMap());

        List<Map<String, Object>> result = new ArrayList<>();

        for (String name : CHANNEL_NAMES) {
            Map<String, Object> channel = channels.get(name);
            boolean enabled = channel != null && Boolean.TRUE.equals(channel.get("enabled"));

            Map<String, Object> info = new HashMap<>();
            info.put("name", name);
            info.put("enabled", enabled);

            // 从运行期 ChannelManager 获取实时 running 状态
            boolean running = false;
            Object rtStatus = runtimeStatus.get(name);
            if (rtStatus instanceof Map<?, ?> rtMap) {
                Object runningVal = rtMap.get("running");
                running = Boolean.TRUE.equals(runningVal);
            }
            info.put("running", running);
            result.add(info);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 获取指定通道的详细配置
     *
     * @param name 通道名称
     * @return 通道详情
     */
    @GetMapping("/{name}")
    public ResponseEntity<Map<String, Object>> getChannelDetail(@PathVariable("name") String name) {
        Map<String, Object> detail = getChannelDetailMap(name);

        if (detail != null) {
            return ResponseEntity.ok(detail);
        } else {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Channel not found");
            return ResponseEntity.status(404).body(error);
        }
    }

    /**
     * 更新指定通道的配置
     *
     * @param name 通道名称
     * @param request 包含更新字段的请求体
     * @return 更新结果
     */
    @PutMapping("/{name}")
    public ResponseEntity<Map<String, Object>> updateChannel(
            @PathVariable("name") String name,
            @RequestBody Map<String, Object> request) {

        if (!CHANNEL_NAMES.contains(name)) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Update failed");
            return ResponseEntity.status(400).body(error);
        }

        ChannelsConfig channels = getChannelsMap();
        Map<String, Object> channel = channels.get(name);

        mergeChannelFields(name, channel, request);
        channels.setObject(name,channel,channels);
        saveChannelsMap(channels);

        // 通道配置更新后，若通道已在运行，尝试重启以应用新配置
        channelService().ifPresent(svc -> {
            try {
                svc.stopChannel(name);
                Map<String, Object> ch = channels.get(name);
                if (ch != null && Boolean.TRUE.equals(ch.get("enabled"))) {
                    svc.startChannel(name);
                }
            } catch (Exception e) {
                log.warn("重启通道失败: channel={}, error={}", name, e.getMessage());
            }
        });

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Channel updated");

        return ResponseEntity.ok(result);
    }

    /**
     * 获取微信扫码登录状态。
     *
     * <p>通过 ChannelService 委托给 channel-plugin 的 WechatChannel.getLoginStatus()。
     */
    @GetMapping("/wechat/login")
    public ResponseEntity<Map<String, Object>> getWechatLoginStatus() {
        return ResponseEntity.ok(
                channelService()
                        .map(ChannelService::getWechatLoginStatus)
                        .orElseGet(() -> {
                            Map<String, Object> status = new HashMap<>();
                            status.put("enabled", false);
                            status.put("running", false);
                            status.put("loggedIn", false);
                            status.put("state", "unavailable");
                            status.put("error", "ChannelService not registered");
                            return status;
                        })
        );
    }

    /**
     * 按需启动微信通道（用户点击 Web 页面扫码登录时调用）。
     *
     * @return 启动后的微信登录状态（含二维码）
     */
    @PostMapping("/wechat/start")
    public ResponseEntity<Map<String, Object>> startWechatLogin() {
        return ResponseEntity.ok(
                channelService()
                        .map(ChannelService::ensureWechatChannel)
                        .orElseGet(() -> {
                            Map<String, Object> status = new HashMap<>();
                            status.put("enabled", false);
                            status.put("running", false);
                            status.put("loggedIn", false);
                            status.put("state", "unavailable");
                            status.put("error", "ChannelService not registered");
                            return status;
                        })
        );
    }

    /**
     * QQ 消息接收 Webhook 端点。
     *
     * <p>外部 qq-gateway 进程将 QQ 平台消息转换为 JSON 后 POST 到此端点，
     * 由 {@link ChannelService} 委托给 channel-plugin 内部的 QQ 通道处理，
     * 避免 ui-plugin 直接依赖 channel-plugin 的内部类型。
     *
     * @param messageJson QQ 消息的 JSON 字符串
     * @return 处理结果
     */
    @PostMapping("/qq/webhook")
    public ResponseEntity<Map<String, Object>> receiveQQMessage(@RequestBody String messageJson) {
        Map<String, Object> result = new HashMap<>();

        Optional<ChannelService> svc = channelService();
        if (svc.isEmpty()) {
            log.error("ChannelService 未注册，无法处理 QQ 消息");
            result.put("success", false);
            result.put("error", "ChannelService not registered");
            return ResponseEntity.status(503).body(result);
        }

        try {
            log.info("收到 QQ 网关转发的消息: length={}, preview={}",
                    messageJson != null ? messageJson.length() : 0,
                    messageJson != null
                            ? messageJson.substring(0, Math.min(100, messageJson.length()))
                            : "null");

            boolean accepted = svc.get().handleIncomingMessage("qq", messageJson);
            if (!accepted) {
                log.error("QQ 通道不可用或未运行");
                result.put("success", false);
                result.put("error", "QQ channel not available or not running");
                return ResponseEntity.status(503).body(result);
            }

            result.put("success", true);
            result.put("message", "Message processed");
            log.info("QQ 消息已成功处理");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("处理 QQ 消息时出错: error={}, type={}", e.getMessage(), e.getClass().getSimpleName());
            result.put("success", false);
            result.put("error", "Failed to process message: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 根据通道名获取详情 Map（敏感字段已掩码）。
     * 不支持的通道名返回 null。
     */
    private Map<String, Object> getChannelDetailMap(String name) {
        if (!CHANNEL_NAMES.contains(name)) {
            return null;
        }
        ChannelsConfig channels = getChannelsMap();
        Map<String, Object> channel = channels.get(name);
        if (channel == null) {
            channel = new LinkedHashMap<>();
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("name", name);
        for (Map.Entry<String, Object> e : channel.entrySet()) {
            String field = e.getKey();
            Object value = e.getValue();
            if (SECRET_FIELDS.getOrDefault(name, Collections.emptyList()).contains(field)
                    && value instanceof String s) {
                detail.put(field, WebUtils.maskSecret(s));
            } else {
                detail.put(field, value);
            }
        }
        return detail;
    }

    /**
     * 将请求中的字段合并到通道配置 Map。
     * 已掩码的敏感字段不会覆盖原有值。
     */
    private void mergeChannelFields(String name, Map<String, Object> channel, Map<String, Object> request) {
        List<String> secrets = SECRET_FIELDS.getOrDefault(name, Collections.emptyList());
        for (Map.Entry<String, Object> e : request.entrySet()) {
            String field = e.getKey();
            Object value = e.getValue();
            if (secrets.contains(field) && value instanceof String s) {
                if (!WebUtils.isSecretMasked(s)) {
                    channel.put(field, s);
                }
            } else {
                channel.put(field, value);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private ChannelsConfig getChannelsMap() {
        ChannelsConfig config = store.getJSON(RedisKeys.NOTIFICATIONS_CHANNELS, ChannelsConfig.class);
        return config;
    }
    /**
     * ChannelsConfig 转为 Map<通道名, Map<字段名, 字段值>>
     *
     * 结果示例：
     * {
     *   "telegram": { "enabled": false, "token": null, "allowFrom": [] },
     *   "feishu":   { "enabled": false, "appId": null, "appSecret": null, ... },
     *   ...
     * }
     */
    public static Map<String, Map<String, Object>> channelsToMap(ChannelsConfig channels) {
        return MAPPER.convertValue(channels, new TypeReference<Map<String, Map<String, Object>>>() {});
    }
    private void saveChannelsMap(ChannelsConfig channels) {
        store.putJSON(RedisKeys.NOTIFICATIONS_CHANNELS, channels);
    }
}
