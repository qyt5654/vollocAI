package com.vollocAI.ai.infra;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 线程池的config管理
 */
@Configuration
public class ThreadPoolConfig {

    @Bean(name = "aiThreadPool", destroyMethod = "shutdown")
    public ThreadPoolExecutor getLabelThreadPool(){
        return new ThreadPoolExecutor(20, 100, 5,
                TimeUnit.SECONDS, new LinkedBlockingDeque<>(40),
                new CustomNameThreadFactory("ai"),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

}
