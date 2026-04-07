package com.cheney.chat.controller;

import com.cheney.chat.model.ChatRequest;
import com.cheney.chat.service.SparkChatService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final SparkChatService sparkChatService;

    public ChatController(SparkChatService sparkChatService) {
        this.sparkChatService = sparkChatService;
    }

    /**
     * 流式对话接口（Server-Sent Events）
     *
     * POST /api/chat
     * Content-Type: application/json
     *
     * {
     *   "messages": [
     *     {"role": "system", "content": "你是一个助手"},
     *     {"role": "user",   "content": "你好"}
     *   ]
     * }
     *
     * 响应：text/event-stream，每帧一个 data 字段，最终发送 event:done data:[DONE]
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest request) {
        // 超时设置为 0 表示不限制（由 service 内部控制）
        SseEmitter emitter = new SseEmitter(0L);
        // 异步执行，立即返回 SSE 流
        new Thread(() -> sparkChatService.chat(request, emitter)).start();
        return emitter;
    }
}
