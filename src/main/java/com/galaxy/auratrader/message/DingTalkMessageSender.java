package com.galaxy.auratrader.message;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class DingTalkMessageSender {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String ROBOT_URI = "https://oapi.dingtalk.com/robot/send";

    private final DingTalkProperties properties;
    private final Clock clock = Clock.systemUTC();

    // Use Java 11 HttpClient and Jackson ObjectMapper instead of RestTemplate
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DingTalkResponse sendText(String content) {


        return sendText(content, null, null, null);
    }

    public DingTalkResponse sendText(String content,
                                     List<String> atUserIds,
                                     List<String> atMobiles,
                                     Boolean atAll) {
        validateRequiredProperties();
        if (!StringUtils.hasText(content)) {
            log.error("DingTalk message content is empty.");
            return new DingTalkResponse(-1, "Message content is empty");
        }

        long timestamp = clock.millis();
        String sign = generateSignature(properties.getSecret(), timestamp);
        // Build URI without relying on Spring's UriComponentsBuilder
        String uriStr = ROBOT_URI
                + "?access_token=" + URLEncoder.encode(properties.getAccessToken(), StandardCharsets.UTF_8)
                + "&timestamp=" + timestamp
                + "&sign=" + sign; // sign is already URL-encoded in generateSignature
        URI uri = URI.create(uriStr);

        Map<String, Object> payload = buildPayload(content, atUserIds, atMobiles, atAll);

        try {
            String json = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Content-Type", "application/json;charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String responseBody = response.body();
            if (responseBody == null) {
                throw new IllegalStateException("DingTalk response body is null");
            }

            JsonNode root = objectMapper.readTree(responseBody);
            int errcode = root.path("errcode").asInt(-1);
            String errmsg = root.path("errmsg").asText(null);
            DingTalkResponse body = new DingTalkResponse(errcode, errmsg);

            if (body.errcode() != 0) {
                log.warn("Failed to send DingTalk message: errcode={} errmsg={}", body.errcode(), body.errmsg());
            } else {
                log.debug("DingTalk message sent successfully.");
            }
            return body;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to send DingTalk message", e);
        }
    }

    private void validateRequiredProperties() {
        if (!StringUtils.hasText(properties.getAccessToken())) {
            throw new IllegalStateException("qtrade.dingtalk.access-token is required");
        }
        if (!StringUtils.hasText(properties.getSecret())) {
            throw new IllegalStateException("qtrade.dingtalk.secret is required");
        }
    }

    private Map<String, Object> buildPayload(String content,
                                             List<String> atUserIds,
                                             List<String> atMobiles,
                                             Boolean atAll) {
        List<String> mergedMobiles = mergeUnique(properties.getDefaultAtMobiles(), atMobiles);
        List<String> mergedUserIds = mergeUnique(properties.getDefaultAtUserIds(), atUserIds);
        boolean shouldAtAll = atAll != null ? atAll : properties.isDefaultAtAll();

        return Map.of(
                "msgtype", "text",
                "text", Map.of("content", content),
                "at", Map.of(
                        "atMobiles", mergedMobiles,
                        "atUserIds", mergedUserIds,
                        "isAtAll", shouldAtAll
                )
        );
    }

    private List<String> mergeUnique(List<String> defaults, List<String> overrides) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        addAll(merged, defaults);
        addAll(merged, overrides);
        return new ArrayList<>(merged);
    }

    private void addAll(LinkedHashSet<String> target, List<String> source) {
        if (source == null) {
            return;
        }
        for (String value : source) {
            if (StringUtils.hasText(value)) {
                target.add(value.trim());
            }
        }
    }

    private String generateSignature(String secret, long timestamp) {
        String stringToSign = timestamp + "\n" + secret;
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            String base64 = java.util.Base64.getEncoder().encodeToString(signData);
            return URLEncoder.encode(base64, StandardCharsets.UTF_8);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to generate DingTalk signature", e);
        }
    }

    public record DingTalkResponse(int errcode, String errmsg) {
    }
}
