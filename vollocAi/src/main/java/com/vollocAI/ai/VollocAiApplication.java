package com.vollocAI.ai;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan({"com.vollocAI.ai.task.dao","com.vollocAI.ai.user.dao","com.vollocAI.ai.model.dao","com.vollocAI.ai.session.dao","com.vollocAI.ai.memory.dao"})
public class VollocAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(VollocAiApplication.class, args);
    }

}
