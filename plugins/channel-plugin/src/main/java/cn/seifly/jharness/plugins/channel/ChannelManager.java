package cn.seifly.jharness.plugins.channel;

import cn.seifly.jharness.plugin.framework.bus.BusClosedException;
import cn.seifly.jharness.plugin.framework.bus.MessageBus;
import cn.seifly.jharness.plugin.framework.bus.OutboundMessage;
import cn.seifly.jharness.plugin.framework.config.ChannelsConfig;
import cn.seifly.jharness.plugins.channel.channels.*;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 所有消息通道的管理器
 * 
 * 负责管理系统中所有可用的消息通道，包括初始化、启动、停止和消息路由：
 * 
 * 核心职责：
 * - 通道初始化：根据配置文件初始化各种消息通道（Telegram、Discord、微信等）
 * - 生命周期管理：统一管理所有通道的启动和停止
 * - 消息路由：将出站消息分发到正确的通道进行发送
 * - 状态监控：跟踪各通道的运行状态
 * 
 * 支持的通道类型：
 * - Telegram：基于Telegram Bot API的即时通讯通道
 * - Discord：基于Discord Bot的聊天通道
 * - WhatsApp：通过桥接服务支持WhatsApp消息
 * - 飞书：企业级协作平台消息通道
 * - 钉钉：阿里巴巴企业通讯平台
 * - QQ：腾讯QQ消息通道
 * - MaixCam：专用摄像头设备通道
 * 
 * 设计特点：
 * - 动态配置：根据配置文件动态决定启用哪些通道
 * - 异步调度：出站消息分发在独立线程中进行
 * - 错误隔离：单个通道的故障不会影响其他通道
 * - 灵活扩展：支持注册自定义通道实现
 *
 */
@Slf4j(topic = "channels")
public class ChannelManager {
    
    private static final int MAX_SEND_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 1000L;
    
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();
    private final MessageBus bus;
    private final ChannelsConfig channelsConfig;
    private volatile boolean dispatchRunning = false;
    private final List<Thread> dispatchThreads = new ArrayList<>();
    
    /** 中断当前任务的回调，由 AgentRuntime 设置 */
    private volatile Runnable abortCurrentTaskCallback;
    
    public ChannelManager(ChannelsConfig channelsConfig, MessageBus bus) {
        this.channelsConfig = channelsConfig;
        this.bus = bus;
        initChannels();
    }

    /**
     * 获取通道配置（供 ChannelService 实现按需启动微信通道等场景使用）。
     */
    public ChannelsConfig getChannelsConfig() {
        return channelsConfig;
    }
    
    /**
     * 设置中断当前任务的回调。
     * 由 AgentRuntime 在初始化时调用，使 ChannelManager 能够触发任务中断。
     */
    public void setAbortCurrentTaskCallback(Runnable callback) {
        this.abortCurrentTaskCallback = callback;
    }
    
    /**
     * 中断当前正在执行的 LLM 任务。
     * 当通道收到 /stop 命令时调用此方法。
     * 
     * @return true 表示成功发送中断信号，false 表示回调未设置或无活跃任务
     */
    public boolean abortCurrentTask() {
        if (abortCurrentTaskCallback != null) {
            try {
                abortCurrentTaskCallback.run();
                log.info("Abort current task callback invoked");
                return true;
            } catch (Exception e) {
                log.error("Failed to invoke abort current task callback: error={}",
                        e.getMessage());
            }
        }
        return false;
    }
    
    private void initChannels() {
        log.info("Initializing channel manager");
        
        initTelegramChannel(channelsConfig);
        initDiscordChannel(channelsConfig);
        initWhatsAppChannel(channelsConfig);
        initWechatChannel(channelsConfig);
        initFeishuChannel(channelsConfig);
        initDingTalkChannel(channelsConfig);
        initQQChannel(channelsConfig);
        initMaixCamChannel(channelsConfig);
        initWeComChannel(channelsConfig);
        
        log.info("Channel initialization completed: enabled_channels={}", channels.size());
    }
    
    /**
     * 初始化 Telegram 通道
     */
    private void initTelegramChannel(ChannelsConfig channelsConfig) {
        if (channelsConfig.getTelegram().isEnabled() 
                && channelsConfig.getTelegram().getToken() != null 
                && !channelsConfig.getTelegram().getToken().isEmpty()) {
            try {
                Channel telegram = new TelegramChannel(channelsConfig.getTelegram(), bus);
                registerChannel("telegram", telegram);
                log.info("Telegram channel enabled successfully");
            } catch (Exception e) {
                log.error("Failed to initialize Telegram channel: error={}", e.getMessage());
            }
        }
    }
    
    /**
     * 初始化 Discord 通道
     */
    private void initDiscordChannel(ChannelsConfig channelsConfig) {
        if (channelsConfig.getDiscord().isEnabled() 
                && channelsConfig.getDiscord().getToken() != null 
                && !channelsConfig.getDiscord().getToken().isEmpty()) {
            try {
                Channel discord = new DiscordChannel(channelsConfig.getDiscord(), bus);
                registerChannel("discord", discord);
                log.info("Discord channel enabled successfully");
            } catch (Exception e) {
                log.error("Failed to initialize Discord channel: error={}", e.getMessage());
            }
        }
    }
    
    /**
     * 初始化 WhatsApp 通道
     */
    private void initWhatsAppChannel(ChannelsConfig channelsConfig) {
        if (channelsConfig.getWhatsapp().isEnabled() 
                && channelsConfig.getWhatsapp().getBridgeUrl() != null 
                && !channelsConfig.getWhatsapp().getBridgeUrl().isEmpty()) {
            try {
                Channel whatsapp = new WhatsAppChannel(channelsConfig.getWhatsapp(), bus);
                registerChannel("whatsapp", whatsapp);
                log.info("WhatsApp channel enabled successfully");
            } catch (Exception e) {
                log.error("Failed to initialize WhatsApp channel: error={}", e.getMessage());
            }
        }
    }

    /**
     * 初始化微信通道
     */
    private void initWechatChannel(ChannelsConfig channelsConfig) {
        if (channelsConfig.getWechat().isEnabled()) {
            try {
                Channel wechat = new WechatChannel(channelsConfig.getWechat(), bus);
                registerChannel("wechat", wechat);
                log.info("Wechat channel enabled successfully");
            } catch (Exception e) {
                log.error("Failed to initialize Wechat channel: error={}", e.getMessage());
            }
        }
    }
    
    /**
     * 初始化飞书通道
     */
    private void initFeishuChannel(ChannelsConfig channelsConfig) {
        if (channelsConfig.getFeishu().isEnabled()) {
            try {
                Channel feishu = new FeishuChannel(channelsConfig.getFeishu(), bus);
                registerChannel("feishu", feishu);
                log.info("Feishu channel enabled successfully");
            } catch (Exception e) {
                log.error("Failed to initialize Feishu channel: error={}", e.getMessage());
            }
        }
    }
    
    /**
     * 初始化钉钉通道
     */
    private void initDingTalkChannel(ChannelsConfig channelsConfig) {
        if (channelsConfig.getDingtalk().isEnabled() 
                && channelsConfig.getDingtalk().getClientId() != null 
                && !channelsConfig.getDingtalk().getClientId().isEmpty()) {
            try {
                Channel dingtalk = new DingTalkChannel(channelsConfig.getDingtalk(), bus);
                registerChannel("dingtalk", dingtalk);
                log.info("DingTalk channel enabled successfully");
            } catch (Exception e) {
                log.error("Failed to initialize DingTalk channel: error={}", e.getMessage());
            }
        }
    }
    
    /**
     * 初始化 QQ 通道
     */
    private void initQQChannel(ChannelsConfig channelsConfig) {
        log.info("检查 QQ 通道配置...");
        
        boolean isEnabled = channelsConfig.getQq().isEnabled();
        String appId = channelsConfig.getQq().getAppId();
        String appSecret = channelsConfig.getQq().getAppSecret();
        
        log.info("QQ 通道配置状态: enabled={}, app_id_present={}, app_secret_present={}",
            isEnabled,
            appId != null && !appId.isEmpty(),
            appSecret != null && !appSecret.isEmpty());
        
        if (!isEnabled) {
            log.info("QQ 通道未启用，跳过初始化");
            return;
        }
        
        if (appId == null || appId.isEmpty()) {
            log.error("QQ 通道已启用但 App ID 为空，无法初始化");
            return;
        }
        
        if (appSecret == null || appSecret.isEmpty()) {
            log.error("QQ 通道已启用但 App Secret 为空，无法初始化");
            return;
        }
        
        try {
            log.info("正在初始化 QQ 通道...: app_id={}***", appId.substring(0, Math.min(8, appId.length())));
            Channel qq = new QQChannel(channelsConfig.getQq(), bus);
            registerChannel("qq", qq);
            log.info("QQ channel enabled successfully: channel={}", "qq");
        } catch (Exception e) {
            log.error("Failed to initialize QQ channel: error={}, error_type={}",
                e.getMessage(), e.getClass().getSimpleName());
        }
    }
    
    /**
     * 初始化 MaixCam 通道
     */
    private void initMaixCamChannel(ChannelsConfig channelsConfig) {
        if (channelsConfig.getMaixcam().isEnabled()) {
            try {
                Channel maixcam = new MaixCamChannel(channelsConfig.getMaixcam(), bus);
                registerChannel("maixcam", maixcam);
                log.info("MaixCam channel enabled successfully");
            } catch (Exception e) {
                log.error("Failed to initialize MaixCam channel: error={}", e.getMessage());
            }
        }
    }

    /**
     * 初始化企业微信通道
     */
    private void initWeComChannel(ChannelsConfig channelsConfig) {
        if (log.isDebugEnabled()) {
            log.debug("Checking WeCom channel configuration: enabled={}",
                channelsConfig.getWecom().isEnabled());
        }
        
        if (!channelsConfig.getWecom().isEnabled()) {
            return;
        }

        ChannelsConfig.WeComConfig wecomConfig = channelsConfig.getWecom();
        
        log.info("WeCom channel enabled, checking configuration: hasBotId={}, hasSecret={}",
            wecomConfig.getBotId() != null && !wecomConfig.getBotId().isEmpty(),
            wecomConfig.getSecret() != null && !wecomConfig.getSecret().isEmpty());

        // Bot 模式：只需要 botId + secret 即可
        boolean hasBotConfig = wecomConfig.getBotId() != null && !wecomConfig.getBotId().isEmpty()
                && wecomConfig.getSecret() != null && !wecomConfig.getSecret().isEmpty();

        if (!hasBotConfig) {
            log.warn("WeCom channel enabled but not configured properly. Need botId+secret for Bot mode, or corpId+secret+token+encodingAesKey for Agent mode");
            return;
        }

        try {
            Channel wecom = new WeComChannel(wecomConfig, bus);
            registerChannel("wecom", wecom);
            log.info("WeCom channel enabled successfully: mode={}",
                hasBotConfig ? "bot" : "agent");
        } catch (Exception e) {
            log.error("Failed to initialize WeCom channel: error={}", e.getMessage());
        }
    }
    
    /**
     * 启动所有通道
     * 
     * 按照以下顺序启动所有已配置的通道：
     * 1. 启动出站消息调度线程
     * 2. 依次启动每个已注册的通道
     * 3. 记录启动过程中的成功和失败情况
     * 
     * 如果没有任何通道被启用，会记录警告信息。
     * 每个通道的启动都是独立的，一个通道的失败不会影响其他通道。
     */
    public void startAll() {
        if (channels.isEmpty()) {
            log.warn("No channels enabled");
            return;
        }
        
        log.info("Starting all channels");
        
        // 为每个通道启动独立的出站调度线程，各通道消费者只消费自己通道的消息
        dispatchRunning = true;
        for (String channelName : channels.keySet()) {
            startDispatcherForChannel(channelName);
        }
        
        // 启动所有通道
        for (Map.Entry<String, Channel> entry : channels.entrySet()) {
            String channelName = entry.getKey();
            Channel channel = entry.getValue();
            
            log.info("Starting channel: channel={}", channelName);
            try {
                channel.start();
            } catch (Exception e) {
                log.error("Failed to start channel: channel={}, error={}",
                        channelName, e.getMessage());
            }
        }
        
        log.info("All channels started");
    }
    
    /**
     * 停止所有通道
     * 
     * 按照以下顺序优雅地停止所有通道：
     * 1. 停止出站消息调度线程
     * 2. 依次停止每个已启动的通道
     * 3. 记录停止过程中的状态
     * 
     * 使用interrupt()方法通知调度线程退出，
     * 各通道应该实现适当的清理逻辑来处理停止请求。
     */
    public void stopAll() {
        log.info("Stopping all channels");
        
        dispatchRunning = false;
        for (Thread dispatchThread : dispatchThreads) {
            dispatchThread.interrupt();
        }
        dispatchThreads.clear();
        
        for (Map.Entry<String, Channel> entry : channels.entrySet()) {
            String channelName = entry.getKey();
            Channel channel = entry.getValue();
            
            log.info("Stopping channel: channel={}", channelName);
            try {
                channel.stop();
            } catch (Exception e) {
                log.error("Error stopping channel: channel={}, error={}",
                        channelName, e.getMessage());
            }
        }
        
        log.info("All channels stopped");
    }
    
    /**
     * 指定通道的出站消息调度循环
     *
     * 每个通道独立运行此方法，只消费属于自己通道的出站消息，互不干扰。
     * 当 dispatchRunning 为 false 或总线关闭时退出循环。
     *
     * @param channelName 负责调度的通道名称
     */
    private void dispatchOutboundForChannel(String channelName) {
        log.info("Outbound dispatcher started: channel={}", channelName);

        Channel channel = channels.get(channelName);
        if (channel == null) {
            log.warn("Dispatcher started for unknown channel, exiting: channel={}", channelName);
            return;
        }

        while (dispatchRunning) {
            try {
                OutboundMessage msg = bus.subscribeOutbound(channelName, 1, java.util.concurrent.TimeUnit.SECONDS);
                if (msg == null) {
                    continue;
                }

                // 消息发送重试逻辑
                boolean sendSuccess = false;
                Exception lastException = null;

                for (int retry = 0; retry <= MAX_SEND_RETRIES; retry++) {
                    try {
                        channel.send(msg);
                        sendSuccess = true;
                        break;
                    } catch (Exception e) {
                        lastException = e;
                        if (retry < MAX_SEND_RETRIES) {
                            try {
                                Thread.sleep(RETRY_DELAY_MS);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                }

                if (!sendSuccess) {
                    log.error("Failed to send message after retries: channel={}, chat_id={}, retries={}, error={}",
                            channelName,
                            msg.getChatId(),
                            String.valueOf(MAX_SEND_RETRIES),
                            lastException != null ? lastException.getMessage() : "unknown");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (BusClosedException e) {
                log.info("MessageBus closed, stopping dispatcher: channel={}", channelName);
                break;
            } catch (Exception e) {
                log.error("Error dispatching outbound message: channel={}, error={}",
                        channelName, e.getMessage());
            }
        }

        log.info("Outbound dispatcher stopped: channel={}", channelName);
    }
    
    /**
     * 根据名称获取通道
     * 
     * 根据通道名称查找已注册的通道实例。
     * 
     * @param name 通道名称（如"telegram"、"discord"等）
     * @return 对应的通道实例，如果未找到则返回空Optional
     */
    public Optional<Channel> getChannel(String name) {
        return Optional.ofNullable(channels.get(name));
    }

    /**
     * 按需启动微信通道。
     *
     * <p>微信 iLink 没有传统密钥配置，用户打开 Web 页面扫码时即可启动登录流程。</p>
     */
    public synchronized WechatChannel ensureWechatChannel(ChannelsConfig.WechatConfig wechatConfig) {
        Channel existing = channels.get("wechat");
        if (existing instanceof WechatChannel wechatChannel) {
            if (!wechatChannel.isRunning()) {
                wechatChannel.start();
            }
            return wechatChannel;
        }

        WechatChannel wechat = new WechatChannel(wechatConfig, bus);
        registerChannel("wechat", wechat);
        if (dispatchRunning) {
            startDispatcherForChannel("wechat");
        }
        wechat.start();
        log.info("Wechat channel started on demand");
        return wechat;
    }

    private void startDispatcherForChannel(String channelName) {
        Thread dispatchThread = new Thread(
                () -> dispatchOutboundForChannel(channelName),
                "channel-dispatcher-" + channelName
        );
        dispatchThread.setDaemon(true);
        dispatchThread.start();
        dispatchThreads.add(dispatchThread);
    }
    
    /**
     * 获取所有通道的状态
     * 
     * 返回系统中所有已注册通道的当前状态信息，包括：
     * - 是否已启用
     * - 是否正在运行
     * 
     * 主要用于健康检查和监控面板显示。
     * 
     * @return 包含各通道状态信息的映射
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        for (Map.Entry<String, Channel> entry : channels.entrySet()) {
            Map<String, Object> channelStatus = new HashMap<>();
            channelStatus.put("enabled", true);
            channelStatus.put("running", entry.getValue().isRunning());
            status.put(entry.getKey(), channelStatus);
        }
        return status;
    }
    
    /**
     * 获取启用的通道名称列表
     * 
     * 返回当前系统中所有已启用通道的名称列表。
     * 
     * @return 通道名称列表
     */
    public List<String> getEnabledChannels() {
        return new ArrayList<>(channels.keySet());
    }
    
    /**
     * 注册通道
     * 
     * 动态注册一个新的通道实例，允许在运行时扩展系统功能。
     * 同时会将 ChannelManager 自身注入到通道中，使通道能够触发任务中断等操作。
     * 
     * @param name 通道名称
     * @param channel 通道实例
     */
    public void registerChannel(String name, Channel channel) {
        channels.put(name, channel);
        channel.setChannelManager(this);
    }
    
    /**
     * 取消注册通道
     * 
     * 从系统中移除指定名称的通道注册信息。
     * 
     * @param name 要取消注册的通道名称
     */
    public void unregisterChannel(String name) {
        channels.remove(name);
    }
    
    /**
     * 启动指定的通道
     * 
     * 根据通道名称启动已注册的通道实例。
     * 
     * @param name 通道名称
     * @return true 表示启动成功，false 表示通道不存在或已在运行
     */
    public boolean startChannel(String name) {
        Channel channel = channels.get(name);
        if (channel == null) {
            log.warn("Channel not found, cannot start: channel={}", name);
            return false;
        }
        
        if (channel.isRunning()) {
            log.info("Channel is already running: channel={}", name);
            return false;
        }
        
        // 如果调度器正在运行，为该通道启动调度线程
        if (dispatchRunning) {
            startDispatcherForChannel(name);
        }
        
        try {
            channel.start();
            log.info("Channel started: channel={}", name);
            return true;
        } catch (Exception e) {
            log.error("Failed to start channel: channel={}, error={}",
                    name, e.getMessage());
            return false;
        }
    }
    
    /**
     * 停止指定的通道
     * 
     * 根据通道名称停止已运行的通道实例。
     * 
     * @param name 通道名称
     * @return true 表示停止成功，false 表示通道不存在或未在运行
     */
    public boolean stopChannel(String name) {
        Channel channel = channels.get(name);
        if (channel == null) {
            log.warn("Channel not found, cannot stop: channel={}", name);
            return false;
        }
        
        if (!channel.isRunning()) {
            log.info("Channel is not running: channel={}", name);
            return false;
        }
        
        try {
            channel.stop();
            log.info("Channel stopped: channel={}", name);
            return true;
        } catch (Exception e) {
            log.error("Failed to stop channel: channel={}, error={}",
                    name, e.getMessage());
            return false;
        }
    }
    
    /**
     * 向特定通道发送消息
     * 
     * 直接向指定的通道发送消息，绕过正常的消息总线路由机制。
     * 主要用于系统内部的直接消息发送需求。
     * 
     * @param channelName 目标通道名称
     * @param chatId 聊天ID
     * @param content 消息内容
     * @throws Exception 如果通道不存在或发送失败
     */
    public void sendToChannel(String channelName, String chatId, String content) throws Exception {
        Channel channel = channels.get(channelName);
        if (channel == null) {
            throw new IllegalArgumentException("Channel " + channelName + " not found");
        }
        
        OutboundMessage msg = new OutboundMessage(channelName, chatId, content);
        channel.send(msg);
    }
}
