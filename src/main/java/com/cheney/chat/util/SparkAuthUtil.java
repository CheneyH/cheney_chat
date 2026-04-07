package com.cheney.chat.util;

import com.cheney.chat.config.SparkProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;

/**
 * 讯飞 WebSocket 通用 URL 鉴权工具
 * 参考：https://www.xfyun.cn/doc/spark/general_url_authentication.html
 */
@Component
public class SparkAuthUtil {

    private static final DateTimeFormatter RFC1123 =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH);

    private final SparkProperties props;

    public SparkAuthUtil(SparkProperties props) {
        this.props = props;
    }

    /**
     * 生成带鉴权参数的 WebSocket URL
     */
    public String buildAuthUrl() {
        try {
            String date = ZonedDateTime.now(ZoneId.of("GMT")).format(RFC1123);
            String host = props.getHost();
            String path = props.getPath();

            // 拼接签名原文
            String signatureOrigin = "host: " + host + "\n"
                    + "date: " + date + "\n"
                    + "GET " + path + " HTTP/1.1";

            // HMAC-SHA256 签名
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    props.getApiSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signBytes = mac.doFinal(signatureOrigin.getBytes(StandardCharsets.UTF_8));
            String signature = Base64.getEncoder().encodeToString(signBytes);

            // 拼接 authorization
            String authorizationOrigin = String.format(
                    "api_key=\"%s\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\"%s\"",
                    props.getApiKey(), signature);
            String authorization = Base64.getEncoder().encodeToString(
                    authorizationOrigin.getBytes(StandardCharsets.UTF_8));

            // 最终 URL
            return "wss://" + host + path
                    + "?authorization=" + URLEncoder.encode(authorization, StandardCharsets.UTF_8)
                    + "&date=" + URLEncoder.encode(date, StandardCharsets.UTF_8)
                    + "&host=" + URLEncoder.encode(host, StandardCharsets.UTF_8);

        } catch (Exception e) {
            throw new RuntimeException("Failed to build Spark auth URL", e);
        }
    }
}
