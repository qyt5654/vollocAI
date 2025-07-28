package com.vollocAI.ai;

import org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@MapperScan("com.vollocAI.ai.dao")
@Import(RocketMQAutoConfiguration.class)
public class VollocAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(VollocAiApplication.class, args);
    }

}
