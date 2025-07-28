package com.vollocAI.ai.config;

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

    @Bean(name = "aiThreadPool")
    public ThreadPoolExecutor getLabelThreadPool(){
        return new ThreadPoolExecutor(20, 100, 5,
                TimeUnit.SECONDS, new LinkedBlockingDeque<>(40),
                new CustomNameThreadFactory("ai"),//设置名称，可根据报错定位
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean(name = "qpstest")
    public ThreadPoolExecutor getQpstest(){
        return new ThreadPoolExecutor(20, 100, 5,
                TimeUnit.SECONDS, new LinkedBlockingDeque<>(200),
                new CustomNameThreadFactory("qps"),//设置名称，可根据报错定位
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

}
