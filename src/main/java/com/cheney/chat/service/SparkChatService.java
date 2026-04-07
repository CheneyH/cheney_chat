package com.cheney.chat.service;

import com.cheney.chat.config.SparkProperties;
import com.cheney.chat.model.ChatRequest;
import com.cheney.chat.util.SparkAuthUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Service
public class SparkChatService {

    private static final Logger log = LoggerFactory.getLogger(SparkChatService.class);

    private final SparkProperties props;
    private final SparkAuthUtil authUtil;
    private final ObjectMapper objectMapper;

    public SparkChatService(SparkProperties props, SparkAuthUtil authUtil, ObjectMapper objectMapper) {
        this.props = props;
        this.authUtil = authUtil;
        this.objectMapper = objectMapper;
    }

    /**
     * 向讯飞 Spark 发送对话请求，通过 SSE 流式返回给客户端。
     */
    public void chat(ChatRequest request, SseEmitter emitter) {
        String url = authUtil.buildAuthUrl();
        CountDownLatch latch = new CountDownLatch(1);
        StringBuilder fullContent = new StringBuilder();

        WebSocketClient client = new WebSocketClient(URI.create(url)) {

            @Override
            public void onOpen(ServerHandshake handshake) {
                try {
                    String payload = buildRequestPayload(request);
                    log.debug("Sending to Spark: {}", payload);
                    send(payload);
                } catch (Exception e) {
                    log.error("Error building request payload", e);
                    emitter.completeWithError(e);
                    latch.countDown();
                }
            }

            @Override
            public void onMessage(String message) {
                try {
                    JsonNode root = objectMapper.readTree(message);
                    JsonNode header = root.path("header");
                    int code = header.path("code").asInt(-1);

                    if (code != 0) {
                        String errMsg = header.path("message").asText("unknown error");
                        log.error("Spark API error code={} msg={}", code, errMsg);
                        emitter.completeWithError(new RuntimeException("Spark error " + code + ": " + errMsg));
                        close();
                        latch.countDown();
                        return;
                    }

                    int status = header.path("status").asInt(0);
                    JsonNode textArr = root.path("payload").path("choices").path("text");

                    if (textArr.isArray()) {
                        for (JsonNode textNode : textArr) {
                            String content = textNode.path("content").asText("");
                            if (!content.isEmpty()) {
                                fullContent.append(content);
                                emitter.send(SseEmitter.event().data(content));
                            }
                        }
                    }

                    // status=2 表示最后一帧
                    if (status == 2) {
                        emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                        emitter.complete();
                        close();
                        latch.countDown();
                    }
                } catch (Exception e) {
                    log.error("Error processing Spark message", e);
                    emitter.completeWithError(e);
                    latch.countDown();
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                log.debug("WebSocket closed code={} reason={}", code, reason);
                latch.countDown();
            }

            @Override
            public void onError(Exception ex) {
                log.error("WebSocket error", ex);
                emitter.completeWithError(ex);
                latch.countDown();
            }
        };

        try {
            client.connectBlocking(10, TimeUnit.SECONDS);
            // 等待响应完成或超时
            boolean finished = latch.await(props.getTimeoutMs(), TimeUnit.MILLISECONDS);
            if (!finished) {
                log.warn("Spark request timed out after {}ms", props.getTimeoutMs());
                emitter.completeWithError(new RuntimeException("Request timed out"));
                client.close();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitter.completeWithError(e);
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }

    private String buildRequestPayload(ChatRequest request) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();

        // header
        ObjectNode header = root.putObject("header");
        header.put("app_id", props.getAppId());

        // parameter
        ObjectNode parameter = root.putObject("parameter");
        ObjectNode chat = parameter.putObject("chat");
        chat.put("domain", props.getDomain());
        chat.put("temperature", request.getTemperature() != null
                ? request.getTemperature() : props.getTemperature());
        chat.put("top_k", props.getTopK());
        chat.put("max_tokens", request.getMaxTokens() != null
                ? request.getMaxTokens() : props.getMaxTokens());

        // payload
        ObjectNode payload = root.putObject("payload");
        ObjectNode messageNode = payload.putObject("message");
        ArrayNode textArr = messageNode.putArray("text");

        for (ChatRequest.Message msg : request.getMessages()) {
            ObjectNode item = textArr.addObject();
            item.put("role", msg.getRole());
            item.put("content", msg.getContent());
        }

        return objectMapper.writeValueAsString(root);
    }
}
