package cn.seifly.jharness.plugin.framework.bus;

import cn.seifly.jharness.plugin.framework.service.Service;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 消息总线 - 用于在通道和 Agent 之间路由消息。
 *
 * <p>由 SDK 共享，作为 workflow-plugin 与 channel-plugin 之间的解耦中介：
 * <ul>
 *   <li>workflow-plugin 消费入站消息、生产出站消息</li>
 *   <li>channel-plugin 生产入站消息、消费出站消息</li>
 * </ul>
 *
 * 采用发布-订阅模式实现组件间解耦：
 * - 入站消息路由：从各种通道接收用户消息并传递给 Agent 处理
 * - 出站消息路由：将 Agent 的响应按 channel 路由到对应的输出通道（per-channel 独立队列）
 * - 消息队列管理：使用有界阻塞队列防止内存溢出
 */
@Slf4j
public class MessageBus implements Service {

    // 队列大小配置（可以通过系统属性覆盖）
    private static final int DEFAULT_QUEUE_SIZE = 100;
    private static final int INBOUND_QUEUE_SIZE = Integer.getInteger(
        "jharness.bus.inbound.queue.size", DEFAULT_QUEUE_SIZE
    );
    private static final int OUTBOUND_QUEUE_SIZE_PER_CHANNEL = Integer.getInteger(
        "jharness.bus.outbound.queue.size", DEFAULT_QUEUE_SIZE
    );

    private final LinkedBlockingQueue<InboundMessage> inbound;

    /**
     * 按 channel 分隔的出站队列，每个通道独立消费。
     */
    private final ConcurrentHashMap<String, LinkedBlockingQueue<OutboundMessage>> outboundByChannel;

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong droppedInboundCount = new AtomicLong(0);
    private final AtomicLong droppedOutboundCount = new AtomicLong(0);

    public MessageBus() {
        this.inbound = new LinkedBlockingQueue<>(INBOUND_QUEUE_SIZE);
        this.outboundByChannel = new ConcurrentHashMap<>();

        log.info("MessageBus initialized: inbound_queue_size={}, outbound_queue_size_per_channel={}",
                INBOUND_QUEUE_SIZE, OUTBOUND_QUEUE_SIZE_PER_CHANNEL);
    }

    /**
     * 发布入站消息到总线
     */
    public void publishInbound(InboundMessage message) {
        if (closed.get()) {
            long dropped = droppedInboundCount.incrementAndGet();
            log.warn("MessageBus is closed, dropping inbound message: channel={}, chat_id={}, total_dropped={}",
                    message.getChannel(), message.getChatId(), dropped);
            return;
        }
        if (!inbound.offer(message)) {
            long dropped = droppedInboundCount.incrementAndGet();
            log.error("Inbound queue full, dropping message: channel={}, chat_id={}, queue_size={}, total_dropped={}",
                    message.getChannel(), message.getChatId(), inbound.size(), dropped);
            return;
        }
        if (log.isDebugEnabled()) {
            log.debug("Published inbound message: channel={}, chat_id={}, queue_size={}",
                    message.getChannel(), message.getChatId(), inbound.size());
        }
    }

    /**
     * 从总线消费入站消息（阻塞式）
     */
    public InboundMessage consumeInbound() throws InterruptedException {
        while (true) {
            InboundMessage message = inbound.poll(1, TimeUnit.SECONDS);
            if (message != null) {
                return message;
            }
            if (closed.get()) {
                throw new BusClosedException("MessageBus is closed");
            }
        }
    }

    /**
     * 带超时的消费入站消息
     */
    public InboundMessage consumeInbound(long timeout, TimeUnit unit) throws InterruptedException {
        return inbound.poll(timeout, unit);
    }

    /**
     * 发布出站消息到总线
     */
    public void publishOutbound(OutboundMessage message) {
        if (closed.get()) {
            long dropped = droppedOutboundCount.incrementAndGet();
            log.warn("MessageBus is closed, dropping outbound message: channel={}, chat_id={}, total_dropped={}",
                    message.getChannel(), message.getChatId(), dropped);
            return;
        }
        LinkedBlockingQueue<OutboundMessage> channelQueue = getOrCreateOutboundQueue(message.getChannel());
        if (!channelQueue.offer(message)) {
            long dropped = droppedOutboundCount.incrementAndGet();
            log.error("Outbound queue full, dropping message: channel={}, chat_id={}, queue_size={}, total_dropped={}",
                    message.getChannel(), message.getChatId(), channelQueue.size(), dropped);
            return;
        }
        if (log.isDebugEnabled()) {
            log.debug("Published outbound message: channel={}, chat_id={}, queue_size={}",
                    message.getChannel(), message.getChatId(), channelQueue.size());
        }
    }

    /**
     * 订阅指定通道的出站消息（阻塞式）
     */
    public OutboundMessage subscribeOutbound(String channel) throws InterruptedException {
        LinkedBlockingQueue<OutboundMessage> channelQueue = getOrCreateOutboundQueue(channel);
        while (true) {
            OutboundMessage message = channelQueue.poll(1, TimeUnit.SECONDS);
            if (message != null) {
                return message;
            }
            if (closed.get()) {
                throw new BusClosedException("MessageBus is closed");
            }
        }
    }

    /**
     * 带超时的订阅指定通道出站消息
     */
    public OutboundMessage subscribeOutbound(String channel, long timeout, TimeUnit unit) throws InterruptedException {
        return getOrCreateOutboundQueue(channel).poll(timeout, unit);
    }

    public boolean hasInbound() {
        return !inbound.isEmpty();
    }

    public int getInboundSize() {
        return inbound.size();
    }

    public int getOutboundSize(String channel) {
        LinkedBlockingQueue<OutboundMessage> channelQueue = outboundByChannel.get(channel);
        return channelQueue == null ? 0 : channelQueue.size();
    }

    public Set<String> getRegisteredChannels() {
        return outboundByChannel.keySet();
    }

    /**
     * 清除所有待处理消息
     */
    public void clear() {
        inbound.clear();
        outboundByChannel.values().forEach(LinkedBlockingQueue::clear);
        if (log.isDebugEnabled()) {
            log.debug("Message bus cleared");
        }
    }

    /**
     * 立即关闭消息总线
     */
    public void close() {
        closed.set(true);
        long totalDroppedInbound = droppedInboundCount.get();
        long totalDroppedOutbound = droppedOutboundCount.get();
        clear();
        log.info("MessageBus closed (immediate): total_dropped_inbound={}, total_dropped_outbound={}",
                totalDroppedInbound, totalDroppedOutbound);
    }

    /**
     * 优雅关闭消息总线（等待队列排空）
     */
    public void drainAndClose(long timeout, TimeUnit unit) throws InterruptedException {
        closed.set(true);
        long deadlineMs = System.currentTimeMillis() + unit.toMillis(timeout);

        while (System.currentTimeMillis() < deadlineMs) {
            boolean allEmpty = inbound.isEmpty()
                && outboundByChannel.values().stream().allMatch(LinkedBlockingQueue::isEmpty);
            if (allEmpty) {
                break;
            }
            Thread.sleep(100);
        }

        long remainingInbound = inbound.size();
        long remainingOutbound = outboundByChannel.values().stream().mapToLong(LinkedBlockingQueue::size).sum();
        clear();

        log.info("MessageBus closed (drain): remaining_inbound_discarded={}, remaining_outbound_discarded={}, total_dropped_inbound={}, total_dropped_outbound={}",
                remainingInbound, remainingOutbound, droppedInboundCount.get(), droppedOutboundCount.get());
    }

    public boolean isClosed() {
        return closed.get();
    }

    public long getDroppedInboundCount() {
        return droppedInboundCount.get();
    }

    public long getDroppedOutboundCount() {
        return droppedOutboundCount.get();
    }

    private LinkedBlockingQueue<OutboundMessage> getOrCreateOutboundQueue(String channel) {
        return outboundByChannel.computeIfAbsent(
            channel,
            key -> new LinkedBlockingQueue<>(OUTBOUND_QUEUE_SIZE_PER_CHANNEL)
        );
    }
}
