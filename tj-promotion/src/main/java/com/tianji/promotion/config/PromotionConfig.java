package com.tianji.promotion.config;

import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

public class PromotionConfig {

    @Bean
    public Executor generateExchangeCodeExecutor(){
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        //1.核心线程池大小
        executor.setCorePoolSize(2);
        //2.最大线程池大小
        executor.setMaxPoolSize(5);
        //3.队列大小
        executor.setQueueCapacity(200);
        //4.线程前缀名称
        executor.setThreadNamePrefix("exchange-code-handler-");
        //5.拒绝策略
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    @Bean
    public Executor calculateSolutionExecutor(){
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        //1.核心线程池大小
        executor.setCorePoolSize(10);
        //2.最大线程池大小
        executor.setMaxPoolSize(10);
        //3.队列大小
        executor.setQueueCapacity(200);
        //4.线程前缀名称
        executor.setThreadNamePrefix("calculate-solution-handler-");
        //5.拒绝策略
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
