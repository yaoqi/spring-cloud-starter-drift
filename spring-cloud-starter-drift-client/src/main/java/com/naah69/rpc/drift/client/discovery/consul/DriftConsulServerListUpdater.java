package com.naah69.rpc.drift.client.discovery.consul;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.naah69.rpc.drift.client.discovery.IServerListUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Consul列表更新器
 *
 * @author naah
 */
public class DriftConsulServerListUpdater implements IServerListUpdater {

    private static final Logger LOGGER = LoggerFactory.getLogger(DriftConsulServerListUpdater.class);

    private final AtomicBoolean isActive = new AtomicBoolean(false);


    /**
     * 初始延迟
     */
    private final long initialDelayMs;

    /**
     * 刷新间隔
     */
    private final long refreshIntervalMs;

    /**
     * 调度任务
     */
    private volatile ScheduledFuture<?> scheduledFuture;

    public DriftConsulServerListUpdater() {
        /**
         * (默认30秒)
         */
        this(30000);
    }

    public DriftConsulServerListUpdater(long refreshIntervalMs) {
        this(0, refreshIntervalMs);
    }

    public DriftConsulServerListUpdater(long initialDelayMs, long refreshIntervalMs) {
        this.initialDelayMs = initialDelayMs;
        this.refreshIntervalMs = refreshIntervalMs;
    }

    /**
     * 懒加载
     * 单例
     */
    private static class LazyHolder {
        private static final int CORE_THREAD = 2;
        private static Thread shutdownThread;

        /**
         * 服务列表定时刷新器
         */
        static ScheduledThreadPoolExecutor serverListRefreshExecutor = null;

        /**
         *创建调度线程池
         */
        static {
            ThreadFactory factory = new ThreadFactoryBuilder()
                    .setNameFormat("DriftConsulServerListUpdater-%d")
                    .setDaemon(true)
                    .build();


            serverListRefreshExecutor = new ScheduledThreadPoolExecutor(CORE_THREAD, factory);

            shutdownThread = new Thread(() -> {

                LOGGER.info("Shutting down the Executor Pool for DriftConsulServerListUpdater");
                try {
                    shutdownExecutorPool();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

            });

            /**
             *添加关闭钩子
             */
            Runtime.getRuntime().addShutdownHook(shutdownThread);
        }

        /**
         * 关闭线程池
         */
        private static void shutdownExecutorPool() throws InterruptedException {
            if (serverListRefreshExecutor != null) {
                serverListRefreshExecutor.shutdownNow();
                if (shutdownThread != null) {
                    try {
                        /**
                         *移除关闭钩子
                         */
                        Runtime.getRuntime().removeShutdownHook(shutdownThread);
                    } catch (IllegalStateException e) {
                        LOGGER.error("Failed to shutdown the Executor Pool for DriftConsulServerListUpdater", e);
                    }
                }

            }
        }
    }

    /**
     * 获取服务列表刷新器（单例）
     */
    private static ScheduledThreadPoolExecutor getRefreshExecutor() {
        return LazyHolder.serverListRefreshExecutor;
    }

    /**
     * 开启定时刷新任务列表
     *
     * @param updateAction
     */
    @Override
    public synchronized void start(IUpdateAction updateAction) {
        if (isActive.compareAndSet(false, true)) {
            Runnable scheduledRunnable = () -> {

                /**
                 *如果关闭状态
                 */
                if (!isActive.get()) {
                    if (scheduledFuture != null) {
                        /**
                         *尝试取消任务
                         */
                        scheduledFuture.cancel(true);
                    }
                    return;
                }

                try {
                    /**
                     *更新服务列表
                     */
                    updateAction.doUpdate();
                } catch (Exception e) {
                    LOGGER.warn("Failed one do update action", e);
                }

            };

            scheduledFuture = getRefreshExecutor().scheduleWithFixedDelay(
                    scheduledRunnable,
                    initialDelayMs,
                    refreshIntervalMs,
                    TimeUnit.MILLISECONDS
            );
        } else {
            LOGGER.info("Already active, no other operation");
        }
    }

    /**
     * 关闭定时刷新
     */
    @Override
    public void stop() {
        if (isActive.compareAndSet(true, false)) {
            if (scheduledFuture != null) {
                scheduledFuture.cancel(true);
            }
        } else {
            LOGGER.info("Not active, no other operation");
        }
    }
}