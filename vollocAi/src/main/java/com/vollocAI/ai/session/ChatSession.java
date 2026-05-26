package com.vollocAI.ai.session;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ChatSession {
    private Long id;
    private String sessionId;
    private Long userId;
    private String title;
    private String messages;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
