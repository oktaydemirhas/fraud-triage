package dev.oktay.fraud.llm;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** Any OpenAI-compatible chat API (DeepSeek, OpenAI, ...) configured through LLM_API_KEY / LLM_BASE_URL / LLM_MODEL. */
@Component
public class OpenAiCompatibleClient implements LlmClient {
    private final RestClient http;
    private final String model;
    private final boolean configured;

    public OpenAiCompatibleClient(
            @Value("${LLM_API_KEY:}") String apiKey,
            @Value("${LLM_BASE_URL:https://api.deepseek.com}") String baseUrl,
            @Value("${LLM_MODEL:deepseek-chat}") String model) {
        URI uri = URI.create(baseUrl);
        boolean local = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
        if (!"https".equals(uri.getScheme()) && !local) throw new IllegalStateException("LLM_BASE_URL must be https");
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(25));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + apiKey).build();
        this.model = model;
        this.configured = !apiKey.isBlank();
    }

    @Override
    public String completeJson(String system, String user) {
        if (!configured) throw new LlmUnavailableException("LLM_API_KEY is not set", null);
        Map<String, Object> body = Map.of(
                "model", model,
                "temperature", 0,
                "max_tokens", 600,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)));
        try {
            JsonNode res = http.post().uri("/chat/completions").contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
            return res.path("choices").path(0).path("message").path("content").asString("");
        } catch (RuntimeException e) {
            // Details stay in the server log; callers only learn that the upstream failed.
            throw new LlmUnavailableException("LLM call failed", e);
        }
    }

    public static class LlmUnavailableException extends RuntimeException {
        public LlmUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
