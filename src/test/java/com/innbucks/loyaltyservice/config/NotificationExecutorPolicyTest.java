package com.innbucks.loyaltyservice.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The notification executor's saturation policy is DROP AND COUNT (owner
 * decision): a full pool must never run the send on the caller (an HTTP thread
 * in a spend, or the scheduler) and must never throw into it — Spring's
 * {@code TaskRejectedException} would roll back a spend over a courtesy
 * message. The expiry sweep's own executor is the deliberate exception.
 */
class NotificationExecutorPolicyTest {

    private final List<ThreadPoolExecutor> toShutDown = new ArrayList<>();
    private final List<ThreadPoolTaskExecutor> springToShutDown = new ArrayList<>();

    @AfterEach
    void shutDown() {
        toShutDown.forEach(ThreadPoolExecutor::shutdownNow);
        springToShutDown.forEach(ThreadPoolTaskExecutor::shutdown);
    }

    @Test
    void aSaturatedPool_dropsAndCounts_neverThrows_neverRunsOnTheCaller() throws Exception {
        MeterRegistry registry = new SimpleMeterRegistry();
        Counter rejected = Counter.builder("loyalty.notify.rejected").register(registry);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1), new DropAndCountPolicy("notification", rejected));
        toShutDown.add(pool);

        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        pool.execute(() -> {
            started.countDown();
            awaitQuietly(release);
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        pool.execute(() -> { }); // fills the queue

        Thread caller = Thread.currentThread();
        AtomicInteger ranOnCaller = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            assertThatCode(() -> pool.execute(() -> {
                if (Thread.currentThread() == caller) {
                    ranOnCaller.incrementAndGet();
                }
            })).doesNotThrowAnyException();
        }

        assertThat(rejected.count()).isEqualTo(3.0);
        assertThat(ranOnCaller.get()).isZero();
        release.countDown();
    }

    @Test
    void withNoRegistry_itStillNeverThrows() {
        DropAndCountPolicy policy = new DropAndCountPolicy("notification", null);
        assertThatCode(() -> policy.rejectedExecution(() -> { }, null)).doesNotThrowAnyException();
    }

    @Test
    void theRealNotificationExecutor_dropsAndCounts_throughSpringsSubmitToo() throws Exception {
        MeterRegistry registry = new SimpleMeterRegistry();
        AsyncConfig config = new AsyncConfig(beanProvider(registry));
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) config.notificationExecutor();
        springToShutDown.add(executor);

        // Registered at 0 at startup, so increase() sees the very first drop.
        Counter counter = registry.find("loyalty.notify.rejected").tag("executor", "notification").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isZero();

        assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                .isInstanceOf(DropAndCountPolicy.class);
        assertThat(executor.getQueueCapacity()).isEqualTo(500);

        // Saturate it: four busy workers + 500 queued, then three more.
        CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < 4 + 500; i++) {
            executor.execute(() -> awaitQuietly(release));
        }
        assertThatCode(() -> executor.execute(() -> { })).doesNotThrowAnyException();
        // @Async hands off through submit(): no TaskRejectedException either.
        assertThatCode(() -> executor.submit(() -> { })).doesNotThrowAnyException();
        assertThatCode(() -> executor.submit(() -> "x")).doesNotThrowAnyException();
        assertThat(counter.count()).isEqualTo(3.0);
        release.countDown();
    }

    @Test
    void theExpirySweepsExecutor_keepsCallerRuns_soAStampedWarningIsNeverDropped() {
        AsyncConfig config = new AsyncConfig(beanProvider(new SimpleMeterRegistry()));
        Executor sweep = config.expiryWarningExecutor();
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) sweep;
        springToShutDown.add(executor);
        assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
    }

    private static org.springframework.beans.factory.ObjectProvider<MeterRegistry> beanProvider(MeterRegistry r) {
        return new StaticListableBeanFactory(Map.of("meterRegistry", r)).getBeanProvider(MeterRegistry.class);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
