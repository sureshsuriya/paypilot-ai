package com.paypilot.service;

import com.paypilot.dto.ai.AiServiceExtractResponse;
import com.paypilot.exception.AiServiceUnavailableException;
import com.paypilot.exception.AiUpstreamException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.LocalDate;
import java.util.Map;

/**
 * Server-to-server HTTP client calling the internal Node.js AI service.
 * Authenticates via X-AI-Service-Secret; never exposed to browser clients.
 */
@Component
public class AiServiceClient {

    private static final String SECRET_HEADER = "X-AI-Service-Secret";

    private final RestClient restClient;
    private final String aiServiceSecret;

    public AiServiceClient(
            @Value("${ai.service.url:http://localhost:3001}") String aiServiceUrl,
            @Value("${ai.service.secret}") String aiServiceSecret) {

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5000);
        requestFactory.setReadTimeout(25000);

        this.restClient = RestClient.builder()
            .baseUrl(aiServiceUrl)
            .requestFactory(requestFactory)
            .build();
        this.aiServiceSecret = aiServiceSecret;
    }

    public AiServiceExtractResponse extractInvoice(String prompt, LocalDate referenceDate) {
        if (aiServiceSecret == null || aiServiceSecret.isBlank()) {
            throw new AiServiceUnavailableException("AI service secret is not configured.");
        }

        try {
            AiServiceExtractResponse body = restClient.post()
                .uri("/api/ai/extract")
                .contentType(MediaType.APPLICATION_JSON)
                .header(SECRET_HEADER, aiServiceSecret)
                .body(Map.of(
                    "prompt", prompt,
                    "referenceDate", referenceDate.toString()
                ))
                .retrieve()
                .body(AiServiceExtractResponse.class);

            if (body == null || body.draft() == null) {
                throw new AiUpstreamException("AI service returned an empty extraction response.");
            }
            return body;
        } catch (ResourceAccessException ex) {
            throw new AiServiceUnavailableException("AI orchestration service is unreachable.");
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 503) {
                throw new AiServiceUnavailableException("AI orchestration service is unavailable or unconfigured.");
            }
            throw new AiUpstreamException("AI orchestration service returned status " + ex.getStatusCode().value());
        }
    }
}

