package com.innbucks.loyaltyservice.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Enables {@link org.springframework.scheduling.annotation.Async @Async} app-wide
 * and provides the three executors messages run on.
 *
 * <h2>{@code notificationExecutor} — every per-request message (drop and count)</h2>
 * Voucher delivery and the sender copy, points earned / redeemed / transferred /
 * adjusted, guest checkout, tenant attach. Every one names it explicitly
 * ({@code @Async("notificationExecutor")}). Submitted AFTER the caller's transaction commits
 * ({@link com.innbucks.loyaltyservice.util.AfterCommit}), so a rolled-back spend
 * never messages anyone.
 * <ul>
 *   <li>core 2 / max 4: the gateway is the bottleneck, not CPU.</li>
 *   <li>Queue capacity 500: a till burst, or a gateway that is slow for a
 *       minute, queues instead of dropping.</li>
 *   <li>Saturated → {@link DropAndCountPolicy}: the message is dropped, counted
 *       on {@code loyalty.notify.rejected{executor="notification"}} and WARNed
 *       (owner decision). It used to be {@code CallerRunsPolicy}, which ran the
 *       SMS/WhatsApp send (up to 30s) on the HTTP thread — before the after-commit
 *       change, while that thread held the spend's row locks. A message is
 *       best-effort; the spend is not.</li>
 * </ul>
 *
 * <h2>{@code expiryWarningExecutor} — the 08:00 bulk sweep only (caller runs)</h2>
 * {@link com.innbucks.loyaltyservice.scheduler.ExpiryWarningSweeper} stamps
 * {@code expiry_warned_at} BEFORE it sends (warn-once), so a dropped warning is
 * one the customer never gets — the opposite trade from above. It therefore has
 * its own pool, sized so the sweep cannot starve the per-request one, and keeps
 * {@code CallerRunsPolicy}: when full, the scheduler thread sends the next
 * warning itself, which is backpressure on the sweep and nothing else (it holds
 * no transaction while dispatching).
 *
 * <h2>{@code billingExecutor} — the invoice email, and the {@code @Async} default (caller runs)</h2>
 * An invoice email is a bill, not a courtesy message, and the nightly invoicing
 * job sends one per merchant in a burst — exactly the shape that would fill the
 * notification pool's queue and be dropped. So it runs on its own small pool
 * that never drops: when full, the invoicing thread sends the email itself,
 * which only slows the nightly job. It is also what a bare {@code @Async}
 * resolves to, so dropping stays something a call site opts into by name, never
 * a default it inherits.
 *
 * <p>All three run each task inside the submitting thread's trace
 * ({@link TracingConfig#traceContextTaskDecorator()}), so a message's log lines
 * carry the {@code traceId} of the request that caused it. The decorator only
 * wraps the task; it changes nothing about queueing or rejection.
 *
 * <p>Uncaught exceptions go to {@link SimpleAsyncUncaughtExceptionHandler}.
 * Every notifier already swallows its own gateway exceptions; this handler is
 * defence in depth for anything that escapes.
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    static final String REJECTED_METRIC = "loyalty.notify.rejected";

    private final ObjectProvider<MeterRegistry> meterRegistry;

    public AsyncConfig(ObjectProvider<MeterRegistry> meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Bean(name = "notificationExecutor")
    public Executor notificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("loyalty-notify-");
        executor.setTaskDecorator(TracingConfig.traceContextTaskDecorator());
        executor.setRejectedExecutionHandler(
                new DropAndCountPolicy("notification", rejectedCounter("notification")));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Bean(name = "expiryWarningExecutor")
    public Executor expiryWarningExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("loyalty-expiry-warn-");
        executor.setTaskDecorator(TracingConfig.traceContextTaskDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Bean(name = "billingExecutor")
    public Executor billingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("loyalty-billing-");
        executor.setTaskDecorator(TracingConfig.traceContextTaskDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /** Registered when the executor is built, so the series exists at 0 from startup. */
    private Counter rejectedCounter(String executorName) {
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry == null) {
            return null;
        }
        return Counter.builder(REJECTED_METRIC)
                .description("Customer messages dropped because the notification executor was saturated")
                .tag("executor", executorName)
                .register(registry);
    }

    @Override
    public Executor getAsyncExecutor() {
        return billingExecutor();
    }

    @Override
    public org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new SimpleAsyncUncaughtExceptionHandler();
    }
}
