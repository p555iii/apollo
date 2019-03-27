package com.ctrip.framework.apollo.biz.message;

import com.ctrip.framework.apollo.biz.entity.ReleaseMessage;
import com.ctrip.framework.apollo.biz.repository.ReleaseMessageRepository;
import com.ctrip.framework.apollo.core.utils.ApolloThreadFactory;
import com.ctrip.framework.apollo.tracer.Tracer;
import com.ctrip.framework.apollo.tracer.spi.Transaction;
import com.google.common.collect.Queues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Jason Song(song_s@ctrip.com)
 */
@Component
public class DatabaseMessageSender implements MessageSender {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseMessageSender.class);
    /**
     * 清理 Message 队列 最大容量
     */
    private static final int CLEAN_QUEUE_MAX_SIZE = 100;
    /**
     * 清理 Message 队列
     */
    private BlockingQueue<Long> toClean = Queues.newLinkedBlockingQueue(CLEAN_QUEUE_MAX_SIZE);
    /**
     * 清理 Message ExecutorService
     */
    private final ExecutorService cleanExecutorService;
    /**
     * 是否停止清理 Message 标识
     */
    private final AtomicBoolean cleanStopped;

    private final ReleaseMessageRepository releaseMessageRepository;

    public DatabaseMessageSender(final ReleaseMessageRepository releaseMessageRepository) {
        cleanExecutorService = Executors.newSingleThreadExecutor(ApolloThreadFactory.create("DatabaseMessageSender", true));
        cleanStopped = new AtomicBoolean(false);
        this.releaseMessageRepository = releaseMessageRepository;
    }

    @Override
    @Transactional
    public void sendMessage(String message, String channel) {
        logger.info("Sending message {} to channel {}", message, channel);
        // 仅允许发送 APOLLO_RELEASE_TOPIC
        if (!Objects.equals(channel, Topics.APOLLO_RELEASE_TOPIC)) {
            logger.warn("Channel {} not supported by DatabaseMessageSender!");
            return;
        }

        Tracer.logEvent("Apollo.AdminService.ReleaseMessage", message);
        Transaction transaction = Tracer.newTransaction("Apollo.AdminService", "sendMessage");
        try {
            // 保存 ReleaseMessage 对象
            ReleaseMessage newMessage = releaseMessageRepository.save(new ReleaseMessage(message));
            // 添加到清理 Message 队列。若队列已满，添加失败，不阻塞等待。
            toClean.offer(newMessage.getId());
            transaction.setStatus(Transaction.SUCCESS);
        } catch (Throwable ex) {
            logger.error("Sending message to database failed", ex);
            transaction.setStatus(ex);
            throw ex;
        } finally {
            transaction.complete();
        }
    }

    @PostConstruct
    private void initialize() {
        //起一个线程 一直尝试去poll数据
        cleanExecutorService.submit(() -> {
            while (!cleanStopped.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    // 拉取
                    Long rm = toClean.poll(1, TimeUnit.SECONDS);
                    if (rm != null) {
                        // 队列非空，处理拉取到的消息
                        cleanMessage(rm);
                    } else {
                        //防止线程空转
                        TimeUnit.SECONDS.sleep(5);
                    }
                } catch (Throwable ex) {
                    Tracer.logError(ex);
                }
            }
        });
    }

    private void cleanMessage(Long id) {
        boolean hasMore = true;
        // 查询对应的 ReleaseMessage 对象，避免已经删除。因为，DatabaseMessageSender 会在多进程中执行。例如：1）Config Service + Admin Service ；2）N * Config Service ；3）N * Admin Service
        // double check in case the release message is rolled back
        ReleaseMessage releaseMessage = releaseMessageRepository.findById(id).orElse(null);
        if (releaseMessage == null) {
            return;
        }
        while (hasMore && !Thread.currentThread().isInterrupted()) {
            // 拉取相同消息内容的 100 条的老消息
            // 老消息的定义：比当前消息编号小，即先发送的
            // 按照 id 升序
            List<ReleaseMessage> messages = releaseMessageRepository.findFirst100ByMessageAndIdLessThanOrderByIdAsc(
                    releaseMessage.getMessage(), releaseMessage.getId());
            // 删除老消息
            releaseMessageRepository.deleteAll(messages);
            // 若拉取不足 100 条，停止删除保留最新的100条数据
            hasMore = messages.size() == 100;

            messages.forEach(toRemove -> Tracer.logEvent(
                    String.format("ReleaseMessage.Clean.%s", toRemove.getMessage()), String.valueOf(toRemove.getId())));
        }
    }

    void stopClean() {
        cleanStopped.set(true);
    }
}
