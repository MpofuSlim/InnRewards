package com.innbucks.loyaltyservice.config;

import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Enables {@link org.springframework.scheduling.annotation.Async @Async} app-wide
 * and provides the executor the guest-checkout notifier runs on. Moving the
 * SMS/WhatsApp fan-out off the HTTP request thread keeps
 * {@code POST /loyalty/shops/{shopId}/guest-checkout} returning as soon as the
 * checkout commits — a wedged notification gateway can never delay or fail the
 * 201 the POS is waiting on.
 *
 * <p>The pool is deliberately small + bounded:
 * <ul>
 *   <li>core 2 / max 4 covers POS-driven walk-in checkout rates, not customer
 *       traffic.</li>
 *   <li>Queue capacity 50: absorbs a short burst; small enough to surface an
 *       upstream stall.</li>
 *   <li>{@link ThreadPoolExecutor.CallerRunsPolicy}: if the queue fills, the
 *       caller runs the task itself rather than dropping a congratulations
 *       attempt silently — at worst that one burst degrades to inline delivery.</li>
 *   <li>Uncaught exceptions go to {@link SimpleAsyncUncaughtExceptionHandler}.
 *       The notifier already swallows its own gateway exceptions; this handler
 *       is defence in depth for anything that escapes.</li>
 * </ul>
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    @Bean(name = "notificationExecutor")
    public Executor notificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("loyalty-notify-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return notificationExecutor();
    }

    @Override
    public org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new SimpleAsyncUncaughtExceptionHandler();
    }
}
