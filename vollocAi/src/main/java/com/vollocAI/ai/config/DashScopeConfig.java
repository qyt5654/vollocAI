package com.vollocAI.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;
import org.springframework.context.i18n.LocaleContextHolder;

import java.time.LocalDateTime;
import java.util.function.Supplier;

@Configuration
public class DashScopeConfig {

    @Bean
    @Description("Get the current date and time in the user's timezone")
    public Supplier<String> getCurrentDateTime() {
        return () -> LocalDateTime.now()
                .atZone(LocaleContextHolder.getTimeZone().toZoneId())
                .toString();
    }
}
