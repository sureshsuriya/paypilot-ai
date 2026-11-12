package com.paypilot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypilot.config.PayPalProperties;
import com.paypilot.dto.paypal.PayPalCaptureResult;
import com.paypilot.dto.paypal.PayPalCreateOrderRequest;
import com.paypilot.dto.paypal.PayPalOrderDetailsResult;
import com.paypilot.dto.paypal.PayPalOrderResult;
import com.paypilot.exception.PayPalApiException;
import com.paypilot.exception.PayPalAuthenticationException;
import com.paypilot.exception.PayPalMalformedResponseException;
import com.paypilot.exception.PayPalUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Backend-only PayPal Sandbox REST v2 client using Spring RestClient.
 *
 * Security & financial guarantees:
 *   - Strictly bound to https://api-m.sandbox.paypal.com with PAYPAL_MODE=sandbox.
 *   - Caches OAuth2 client-credentials tokens in memory and refreshes shortly before expiry.
 *   - Passes caller-supplied PayPal-Request-Id verbatim for idempotent order creation.
 *   - Never logs client secrets, access tokens, Authorization headers, or full PayPal payloads.
 *   - Never treats incomplete, unknown, or PENDING captures as completed payments.
 */
@Slf4j
@Service
public class PayPalSandboxClient {

    private static final String OAUTH_TOKEN_PATH = "/v1/oauth2/token";
    private static final String ORDERS_PATH = "/v2/checkout/orders";
    private static final String PAYPAL_REQUEST_ID_HEADER = "PayPal-Request-Id";
    private static final String PREFER_HEADER = "Prefer";
    private static final String PREFER_REPRESENTATION = "return=representation";

    private static final Pattern ISO_CURRENCY_PATTERN = Pattern.compile("^[A-Z]{3}$");
    private static final Pattern ORDER_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern SAFE_CODE_PATTERN = Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");

    private static final Set<String> VALID_CREATE_ORDER_STATUSES = Set.of(
        "CREATED", "PAYER_ACTION_REQUIRED", "APPROVED"
    );

    private static final Set<String> RECOGNIZED_CAPTURE_STATUSES = Set.of(
        "COMPLETED", "PENDING", "DECLINED", "FAILED", "PARTIALLY_REFUNDED", "REFUNDED"
    );

    private final PayPalProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    private final ReentrantLock tokenLock = new ReentrantLock();
    private volatile CachedToken cachedToken;

    @Autowired
    public PayPalSandboxClient(
            PayPalProperties properties,
            @Qualifier("payPalRestClient") RestClient restClient,
            ObjectMapper objectMapper,
            @Qualifier("payPalClock") Clock clock) {
        properties.validateSandboxConfiguration();
        this.properties = properties;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Package-private constructor enabling deterministic MockRestServiceServer unit testing
     * without overwriting the mock request factory.
     */
    PayPalSandboxClient(
            PayPalProperties properties,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            Clock clock) {
        String canonicalBaseUrl = properties.canonicalBaseUrl();
        this.properties = properties;
        this.restClient = restClientBuilder.baseUrl(canonicalBaseUrl).build();
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Creates a PayPal Sandbox v2 checkout order using a caller-provided idempotency key
     * passed unchanged in the PayPal-Request-Id header.
     */
    public PayPalOrderResult createOrder(PayPalCreateOrderRequest request) {
        properties.validateSandboxConfiguration();
        validateCreateOrderRequest(request);

        BigDecimal normalizedAmount = request.amount().setScale(2, RoundingMode.HALF_UP);
        String normalizedCurrency = request.currency().trim().toUpperCase(Locale.ROOT);
        Map<String, Object> payload = buildCreateOrderPayload(request, normalizedAmount, normalizedCurrency);

        String accessToken = getValidAccessToken();
        String rawResponse;
        try {
            rawResponse = restClient.post()
                .uri(ORDERS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header(PREFER_HEADER, PREFER_REPRESENTATION)
                .header(PAYPAL_REQUEST_ID_HEADER, request.idempotencyKey())
                .headers(headers -> headers.setBearerAuth(accessToken))
                .body(payload)
                .retrieve()
                .body(String.class);
        } catch (ResourceAccessException ex) {
            throw handleNetworkFailure("create order");
        } catch (RestClientResponseException ex) {
            throw handleOrdersApiError(ex, "create order");
        }

        return parseCreateOrderResponse(
            rawResponse, request.idempotencyKey(), normalizedAmount, normalizedCurrency
        );
    }

    /**
     * Captures a previously approved PayPal Sandbox order.
     */
    public PayPalCaptureResult captureOrder(String paypalOrderId) {
        return captureOrder(paypalOrderId, null);
    }

    /**
     * Captures a previously approved PayPal Sandbox order with an optional caller-supplied PayPal-Request-Id.
     */
    public PayPalCaptureResult captureOrder(String paypalOrderId, String idempotencyKey) {
        properties.validateSandboxConfiguration();
        String validatedOrderId = validateOrderId(paypalOrderId);
        if (idempotencyKey != null) {
            validateIdempotencyKey(idempotencyKey);
        }

        String accessToken = getValidAccessToken();
        String rawResponse;
        try {
            RestClient.RequestBodySpec spec = restClient.post()
                .uri(ORDERS_PATH + "/{orderId}/capture", validatedOrderId)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header(PREFER_HEADER, PREFER_REPRESENTATION)
                .headers(headers -> headers.setBearerAuth(accessToken));

            if (idempotencyKey != null) {
                spec = spec.header(PAYPAL_REQUEST_ID_HEADER, idempotencyKey);
            }

            rawResponse = spec
                .body("{}")
                .retrieve()
                .body(String.class);
        } catch (ResourceAccessException ex) {
            throw handleNetworkFailure("capture order");
        } catch (RestClientResponseException ex) {
            throw handleOrdersApiError(ex, "capture order");
        }

        return parseCaptureOrderResponse(rawResponse, validatedOrderId);
    }

    /**
     * Fetches current PayPal Sandbox order details (GET /v2/checkout/orders/{id})
     * so PENDING captures can be verified safely without blindly repeating POST /capture.
     */
    public PayPalOrderDetailsResult getOrderDetails(String paypalOrderId) {
        properties.validateSandboxConfiguration();
        String validatedOrderId = validateOrderId(paypalOrderId);

        String accessToken = getValidAccessToken();
        String rawResponse;
        try {
            rawResponse = restClient.get()
                .uri(ORDERS_PATH + "/{orderId}", validatedOrderId)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .body(String.class);
        } catch (ResourceAccessException ex) {
            throw handleNetworkFailure("get order details");
        } catch (RestClientResponseException ex) {
            throw handleOrdersApiError(ex, "get order details");
        }

        return parseOrderDetailsResponse(rawResponse, validatedOrderId);
    }

    private String getValidAccessToken() {
        CachedToken current = this.cachedToken;
        Instant now = clock.instant();
        if (current != null && now.isBefore(current.refreshAt())) {
            return current.accessToken();
        }

        tokenLock.lock();
        try {
            current = this.cachedToken;
            now = clock.instant();
            if (current != null && now.isBefore(current.refreshAt())) {
                return current.accessToken();
            }

            CachedToken refreshed = fetchOAuthAccessToken(now);
            this.cachedToken = refreshed;
            return refreshed.accessToken();
        } finally {
            tokenLock.unlock();
        }
    }

    private CachedToken fetchOAuthAccessToken(Instant requestStart) {
        properties.validateSandboxConfiguration();
        properties.validateCredentialsPresent();

        MultiValueMap<String, String> formBody = new LinkedMultiValueMap<>();
        formBody.add("grant_type", "client_credentials");

        String rawResponse;
        try {
            rawResponse = restClient.post()
                .uri(OAUTH_TOKEN_PATH)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.setBasicAuth(
                    properties.getClientId(), properties.getClientSecret(), StandardCharsets.UTF_8))
                .body(formBody)
                .retrieve()
                .body(String.class);
        } catch (ResourceAccessException ex) {
            throw handleNetworkFailure("OAuth token acquisition");
        } catch (RestClientResponseException ex) {
            throw handleOAuthHttpError(ex);
        }

        JsonNode root = parseJsonObject(rawResponse, "OAuth token");
        String token = requiredText(root, "access_token", "OAuth token response is missing access_token.");
        JsonNode expiresInNode = root.path("expires_in");
        if (!expiresInNode.isIntegralNumber() && !expiresInNode.isNumber()) {
            throw new PayPalMalformedResponseException("OAuth token response is missing numeric expires_in.");
        }
        long expiresInSeconds = expiresInNode.asLong();
        if (expiresInSeconds <= 0) {
            throw new PayPalMalformedResponseException("OAuth token response contained non-positive expires_in.");
        }

        long bufferSeconds = properties.getTokenRefreshBufferSeconds();
        long effectiveTtlSeconds = expiresInSeconds > bufferSeconds
            ? (expiresInSeconds - bufferSeconds)
            : Math.max(1L, expiresInSeconds / 2L);

        log.debug("Acquired PayPal Sandbox OAuth token (expiresInSeconds={}, cacheTtlSeconds={})",
            expiresInSeconds, effectiveTtlSeconds);

        return new CachedToken(token, requestStart.plusSeconds(effectiveTtlSeconds));
    }

    private Map<String, Object> buildCreateOrderPayload(
            PayPalCreateOrderRequest request,
            BigDecimal normalizedAmount,
            String normalizedCurrency) {
        String invoiceRef = truncate(request.invoiceNumber().trim(), 127);

        Map<String, Object> purchaseUnit = new LinkedHashMap<>();
        purchaseUnit.put("reference_id", invoiceRef);
        purchaseUnit.put("invoice_id", invoiceRef);
        if (request.customId() != null && !request.customId().isBlank()) {
            purchaseUnit.put("custom_id", truncate(request.customId().trim(), 127));
        }
        purchaseUnit.put("description", truncate(request.description().trim(), 127));
        purchaseUnit.put("amount", Map.of(
            "currency_code", normalizedCurrency,
            "value", normalizedAmount.toPlainString()
        ));

        Map<String, Object> experienceContext = new LinkedHashMap<>();
        experienceContext.put("payment_method_preference", "IMMEDIATE_PAYMENT_REQUIRED");
        experienceContext.put("user_action", "PAY_NOW");
        experienceContext.put("return_url", request.returnUrl().trim());
        experienceContext.put("cancel_url", request.cancelUrl().trim());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("intent", "CAPTURE");
        payload.put("purchase_units", List.of(purchaseUnit));
        payload.put("payment_source", Map.of(
            "paypal", Map.of("experience_context", experienceContext)
        ));
        return payload;
    }

    private PayPalOrderResult parseCreateOrderResponse(
            String rawResponse,
            String idempotencyKey,
            BigDecimal expectedAmount,
            String expectedCurrency) {
        JsonNode root = parseJsonObject(rawResponse, "create order");
        String orderId = validateOrderId(requiredText(root, "id", "PayPal create order response is missing id."));
        String orderStatus = requiredText(root, "status", "PayPal create order response is missing status.")
            .toUpperCase(Locale.ROOT);

        if (!VALID_CREATE_ORDER_STATUSES.contains(orderStatus)) {
            throw new PayPalMalformedResponseException(
                "PayPal create order response returned unexpected status: " + sanitizeCode(orderStatus)
            );
        }

        ApprovalLink approvalLink = extractApprovalLink(root.path("links"));

        BigDecimal responseAmount = expectedAmount;
        String responseCurrency = expectedCurrency;
        JsonNode purchaseUnits = root.path("purchase_units");
        if (!purchaseUnits.isMissingNode() && !purchaseUnits.isNull()) {
            if (!purchaseUnits.isArray() || purchaseUnits.isEmpty()) {
                throw new PayPalMalformedResponseException(
                    "PayPal create order response contained invalid purchase_units array."
                );
            }
            JsonNode amountNode = purchaseUnits.get(0).path("amount");
            if (!amountNode.isMissingNode() && !amountNode.isNull()) {
                responseAmount = parsePositiveAmount(
                    requiredText(amountNode, "value", "PayPal order purchase unit is missing amount.value.")
                );
                responseCurrency = parseIsoCurrency(
                    requiredText(amountNode, "currency_code", "PayPal order purchase unit is missing currency_code.")
                );
                if (responseAmount.compareTo(expectedAmount) != 0 || !responseCurrency.equals(expectedCurrency)) {
                    throw new PayPalMalformedResponseException(
                        "PayPal create order response amount or currency did not match requested invoice values."
                    );
                }
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("orderId", orderId);
        summary.put("status", orderStatus);
        summary.put("approvalLinkRel", approvalLink.rel());
        summary.put("idempotencyKey", idempotencyKey);
        summary.put("amount", responseAmount.toPlainString());
        summary.put("currency", responseCurrency);
        String intent = optionalText(root, "intent");
        if (intent != null) {
            summary.put("intent", intent);
        }
        String createTime = optionalText(root, "create_time");
        if (createTime != null) {
            summary.put("createTime", createTime);
        }

        log.debug("Created PayPal Sandbox order: orderId={}, status={}, idempotencyKey={}",
            orderId, orderStatus, idempotencyKey);

        return new PayPalOrderResult(
            orderId,
            orderStatus,
            approvalLink.href(),
            approvalLink.rel(),
            idempotencyKey,
            responseAmount,
            responseCurrency,
            Map.copyOf(summary)
        );
    }

    private PayPalCaptureResult parseCaptureOrderResponse(String rawResponse, String expectedOrderId) {
        JsonNode root = parseJsonObject(rawResponse, "capture order");
        String orderId = requiredText(root, "id", "PayPal capture response is missing order id.");
        if (!expectedOrderId.equals(orderId)) {
            throw new PayPalMalformedResponseException(
                "PayPal capture response order id did not match requested order id."
            );
        }

        String orderStatus = requiredText(root, "status", "PayPal capture response is missing order status.")
            .toUpperCase(Locale.ROOT);

        JsonNode purchaseUnits = root.path("purchase_units");
        if (!purchaseUnits.isArray() || purchaseUnits.isEmpty()) {
            throw new PayPalMalformedResponseException(
                "PayPal capture response is missing purchase_units."
            );
        }

        JsonNode captures = purchaseUnits.get(0).path("payments").path("captures");
        if (!captures.isArray() || captures.isEmpty()) {
            throw new PayPalMalformedResponseException(
                "PayPal capture response is missing payments.captures details."
            );
        }

        PayPalCaptureResult result = parseCaptureEntry(captures.get(0), orderId, orderStatus, "capture");
        log.debug("Captured PayPal Sandbox order: orderId={}, orderStatus={}, captureId={}, captureStatus={}, pendingReason={}",
            result.paypalOrderId(), result.orderStatus(), result.paypalCaptureId(),
            result.captureStatus(), result.pendingReason());
        return result;
    }

    private PayPalOrderDetailsResult parseOrderDetailsResponse(String rawResponse, String expectedOrderId) {
        JsonNode root = parseJsonObject(rawResponse, "get order details");
        String orderId = requiredText(root, "id", "PayPal order details response is missing order id.");
        if (!expectedOrderId.equals(orderId)) {
            throw new PayPalMalformedResponseException(
                "PayPal order details response order id did not match requested order id."
            );
        }

        String orderStatus = requiredText(root, "status", "PayPal order details response is missing status.")
            .toUpperCase(Locale.ROOT);

        BigDecimal orderAmount = null;
        String orderCurrency = null;
        List<PayPalCaptureResult> parsedCaptures = new java.util.ArrayList<>();

        JsonNode purchaseUnits = root.path("purchase_units");
        if (purchaseUnits.isArray() && !purchaseUnits.isEmpty()) {
            JsonNode firstUnit = purchaseUnits.get(0);
            JsonNode amountNode = firstUnit.path("amount");
            if (amountNode.isObject()) {
                orderAmount = parsePositiveAmount(
                    requiredText(amountNode, "value", "PayPal order details purchase unit is missing amount.value.")
                );
                orderCurrency = parseIsoCurrency(
                    requiredText(amountNode, "currency_code", "PayPal order details purchase unit is missing currency_code.")
                );
            }

            JsonNode captures = firstUnit.path("payments").path("captures");
            if (!captures.isMissingNode() && !captures.isNull()) {
                if (!captures.isArray()) {
                    throw new PayPalMalformedResponseException(
                        "PayPal order details payments.captures field must be an array."
                    );
                }
                for (JsonNode capture : captures) {
                    parsedCaptures.add(parseCaptureEntry(capture, orderId, orderStatus, "order details"));
                }
            }
        }

        log.debug("Fetched PayPal Sandbox order details: orderId={}, orderStatus={}, captureCount={}",
            orderId, orderStatus, parsedCaptures.size());

        return new PayPalOrderDetailsResult(
            orderId,
            orderStatus,
            orderAmount,
            orderCurrency,
            List.copyOf(parsedCaptures)
        );
    }

    private PayPalCaptureResult parseCaptureEntry(
            JsonNode capture,
            String orderId,
            String orderStatus,
            String operationContext) {
        String captureId = requiredText(capture, "id", "PayPal " + operationContext + " is missing capture id.");
        String captureStatus = requiredText(capture, "status", "PayPal " + operationContext + " is missing capture status.")
            .toUpperCase(Locale.ROOT);

        if (!RECOGNIZED_CAPTURE_STATUSES.contains(captureStatus)) {
            throw new PayPalMalformedResponseException(
                "PayPal " + operationContext + " contained unrecognized capture status: " + sanitizeCode(captureStatus)
            );
        }

        JsonNode amountNode = capture.path("amount");
        if (!amountNode.isObject()) {
            throw new PayPalMalformedResponseException("PayPal " + operationContext + " is missing capture amount.");
        }
        BigDecimal captureAmount = parsePositiveAmount(
            requiredText(amountNode, "value", "PayPal " + operationContext + " is missing amount.value.")
        );
        String captureCurrency = parseIsoCurrency(
            requiredText(amountNode, "currency_code", "PayPal " + operationContext + " is missing amount.currency_code.")
        );

        String pendingReason = optionalText(capture.path("status_details"), "reason");
        String createTime = optionalText(capture, "create_time");
        String updateTime = optionalText(capture, "update_time");
        String captureTime = updateTime != null ? updateTime : createTime;

        boolean completed = "COMPLETED".equals(captureStatus) && "COMPLETED".equals(orderStatus);
        boolean pending = "PENDING".equals(captureStatus);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("orderId", orderId);
        summary.put("orderStatus", orderStatus);
        summary.put("captureId", captureId);
        summary.put("captureStatus", captureStatus);
        summary.put("amount", captureAmount.toPlainString());
        summary.put("currency", captureCurrency);
        if (pendingReason != null) {
            summary.put("pendingReason", pendingReason);
        }
        JsonNode finalCaptureNode = capture.path("final_capture");
        if (finalCaptureNode.isBoolean()) {
            summary.put("finalCapture", finalCaptureNode.asBoolean());
        }
        if (createTime != null) {
            summary.put("createTime", createTime);
        }
        if (updateTime != null) {
            summary.put("updateTime", updateTime);
        }

        return new PayPalCaptureResult(
            orderId,
            orderStatus,
            captureId,
            captureStatus,
            completed,
            pending,
            pendingReason,
            captureAmount,
            captureCurrency,
            captureTime,
            Map.copyOf(summary)
        );
    }

    private ApprovalLink extractApprovalLink(JsonNode linksNode) {
        if (!linksNode.isArray()) {
            throw new PayPalMalformedResponseException(
                "PayPal create order response is missing HATEOAS links array."
            );
        }

        ApprovalLink fallbackApprove = null;
        for (JsonNode link : linksNode) {
            String rel = optionalText(link, "rel");
            if (rel == null) {
                continue;
            }
            if ("payer-action".equalsIgnoreCase(rel)) {
                String href = validateSandboxApprovalUrl(
                    requiredText(link, "href", "PayPal payer-action link is missing href.")
                );
                return new ApprovalLink("payer-action", href);
            }
            if ("approve".equalsIgnoreCase(rel) && fallbackApprove == null) {
                String href = validateSandboxApprovalUrl(
                    requiredText(link, "href", "PayPal approve link is missing href.")
                );
                fallbackApprove = new ApprovalLink("approve", href);
            }
        }

        if (fallbackApprove != null) {
            return fallbackApprove;
        }

        throw new PayPalMalformedResponseException(
            "PayPal create order response did not contain a payer-action or approve link."
        );
    }

    private String validateSandboxApprovalUrl(String href) {
        URI uri;
        try {
            uri = URI.create(href.trim());
        } catch (IllegalArgumentException ex) {
            throw new PayPalMalformedResponseException("PayPal approval link is not a valid URI.");
        }
        String scheme = uri.getScheme();
        String host = uri.getHost() != null ? uri.getHost().toLowerCase(Locale.ROOT) : "";
        boolean isSandboxPayPalHost = "sandbox.paypal.com".equals(host) || host.endsWith(".sandbox.paypal.com");
        if (!"https".equalsIgnoreCase(scheme) || !isSandboxPayPalHost) {
            throw new PayPalMalformedResponseException(
                "PayPal approval link must be an HTTPS PayPal Sandbox URL."
            );
        }
        return uri.toString();
    }

    private RuntimeException handleOAuthHttpError(RestClientResponseException ex) {
        int status = ex.getStatusCode().value();
        String errorCode = extractSafeJsonCode(ex.getResponseBodyAsString(), "error");
        log.warn("PayPal Sandbox OAuth request failed: status={}, errorCode={}", status, errorCode);

        if (status >= 400 && status < 500) {
            return new PayPalAuthenticationException(
                status,
                errorCode,
                "PayPal Sandbox OAuth authentication failed (HTTP " + status
                    + (errorCode != null ? ", " + errorCode : "") + ")."
            );
        }
        return new PayPalApiException(
            status,
            errorCode,
            null,
            null,
            "PayPal Sandbox OAuth endpoint returned HTTP " + status + "."
        );
    }

    private RuntimeException handleOrdersApiError(RestClientResponseException ex, String operation) {
        int status = ex.getStatusCode().value();
        SafePayPalError safeError = extractSafeOrdersError(ex.getResponseBodyAsString());

        if (status == 401) {
            this.cachedToken = null;
            log.warn("PayPal Sandbox rejected OAuth token during {}: status={}, debugId={}",
                operation, status, safeError.debugId());
            return new PayPalAuthenticationException(
                status,
                safeError.name(),
                "PayPal Sandbox rejected authentication during " + operation + " (HTTP 401)."
            );
        }
        if (status == 403) {
            log.warn("PayPal Sandbox denied authorization during {}: status={}, debugId={}",
                operation, status, safeError.debugId());
            return new PayPalAuthenticationException(
                status,
                safeError.name(),
                "PayPal Sandbox denied authorization during " + operation + " (HTTP 403)."
            );
        }

        log.warn("PayPal Sandbox {} error: status={}, name={}, issue={}, debugId={}",
            operation, status, safeError.name(), safeError.issue(), safeError.debugId());

        return new PayPalApiException(
            status,
            safeError.name(),
            safeError.issue(),
            safeError.debugId(),
            "PayPal Sandbox " + operation + " failed with HTTP " + status
                + (safeError.name() != null ? " (" + safeError.name() + ")" : "") + "."
        );
    }

    private PayPalUnavailableException handleNetworkFailure(String operation) {
        log.warn("PayPal Sandbox endpoint unreachable or timed out during {}", operation);
        return new PayPalUnavailableException(
            "PayPal Sandbox is temporarily unreachable or timed out during " + operation + "."
        );
    }

    private JsonNode parseJsonObject(String rawBody, String operation) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new PayPalMalformedResponseException(
                "PayPal Sandbox " + operation + " returned an empty response."
            );
        }
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            if (root == null || !root.isObject()) {
                throw new PayPalMalformedResponseException(
                    "PayPal Sandbox " + operation + " response must be a JSON object."
                );
            }
            return root;
        } catch (JsonProcessingException ex) {
            throw new PayPalMalformedResponseException(
                "PayPal Sandbox " + operation + " returned malformed JSON."
            );
        }
    }

    private SafePayPalError extractSafeOrdersError(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return new SafePayPalError(null, null, null);
        }
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            if (root == null || !root.isObject()) {
                return new SafePayPalError(null, null, null);
            }
            String name = sanitizeCode(optionalText(root, "name"));
            String debugId = sanitizeCode(optionalText(root, "debug_id"));
            String issue = null;
            JsonNode details = root.path("details");
            if (details.isArray() && !details.isEmpty()) {
                issue = sanitizeCode(optionalText(details.get(0), "issue"));
            }
            return new SafePayPalError(name, issue, debugId);
        } catch (JsonProcessingException ex) {
            return new SafePayPalError(null, null, null);
        }
    }

    private String extractSafeJsonCode(String rawBody, String fieldName) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            if (root != null && root.isObject()) {
                return sanitizeCode(optionalText(root, fieldName));
            }
        } catch (JsonProcessingException ignored) {
            // Do not expose raw unparseable payloads
        }
        return null;
    }

    private String requiredText(JsonNode node, String field, String errorMessage) {
        if (node == null || node.isMissingNode() || !node.hasNonNull(field)) {
            throw new PayPalMalformedResponseException(errorMessage);
        }
        JsonNode valueNode = node.get(field);
        if (!valueNode.isTextual()) {
            throw new PayPalMalformedResponseException(errorMessage);
        }
        String text = valueNode.asText().trim();
        if (text.isEmpty()) {
            throw new PayPalMalformedResponseException(errorMessage);
        }
        return text;
    }

    private String optionalText(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || !node.hasNonNull(field)) {
            return null;
        }
        JsonNode valueNode = node.get(field);
        if (!valueNode.isTextual()) {
            return null;
        }
        String text = valueNode.asText().trim();
        return text.isEmpty() ? null : text;
    }

    private BigDecimal parsePositiveAmount(String rawAmount) {
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(rawAmount);
        } catch (NumberFormatException ex) {
            throw new PayPalMalformedResponseException("PayPal response contained invalid monetary amount.");
        }
        if (parsed.compareTo(BigDecimal.ZERO) <= 0) {
            throw new PayPalMalformedResponseException("PayPal response contained non-positive monetary amount.");
        }
        return parsed.setScale(2, RoundingMode.HALF_UP);
    }

    private String parseIsoCurrency(String rawCurrency) {
        String normalized = rawCurrency.trim().toUpperCase(Locale.ROOT);
        if (!ISO_CURRENCY_PATTERN.matcher(normalized).matches()) {
            throw new PayPalMalformedResponseException("PayPal response contained invalid ISO currency code.");
        }
        return normalized;
    }

    private void validateCreateOrderRequest(PayPalCreateOrderRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("PayPal create order request must not be null.");
        }
        validateIdempotencyKey(request.idempotencyKey());
        if (request.invoiceNumber() == null || request.invoiceNumber().isBlank()) {
            throw new IllegalArgumentException("Invoice number is required to create a PayPal order.");
        }
        if (request.description() == null || request.description().isBlank()) {
            throw new IllegalArgumentException("Invoice description is required to create a PayPal order.");
        }
        if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Invoice amount must be strictly positive.");
        }
        if (request.currency() == null
                || !ISO_CURRENCY_PATTERN.matcher(request.currency().trim().toUpperCase(Locale.ROOT)).matches()) {
            throw new IllegalArgumentException("Currency must be a valid 3-letter ISO code.");
        }
        validateRedirectUrl(request.returnUrl(), "returnUrl");
        validateRedirectUrl(request.cancelUrl(), "cancelUrl");
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("PayPal-Request-Id idempotency key is required.");
        }
        if (idempotencyKey.length() > 108 || idempotencyKey.indexOf('\r') >= 0 || idempotencyKey.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("PayPal-Request-Id idempotency key format is invalid.");
        }
    }

    private String validateOrderId(String paypalOrderId) {
        if (paypalOrderId == null || !ORDER_ID_PATTERN.matcher(paypalOrderId.trim()).matches()) {
            throw new PayPalMalformedResponseException("PayPal order ID is missing or invalid.");
        }
        return paypalOrderId.trim();
    }

    private void validateRedirectUrl(String url, String fieldName) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(fieldName + " must be a valid URL.");
        }
        String scheme = uri.getScheme();
        boolean validScheme = "https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme);
        if (!validScheme || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be an HTTP/HTTPS URL with a valid host.");
        }
    }

    private String sanitizeCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        return SAFE_CODE_PATTERN.matcher(trimmed).matches() ? trimmed : null;
    }

    private String truncate(String value, int maxLen) {
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }

    private record CachedToken(String accessToken, Instant refreshAt) {
        @Override
        public String toString() {
            return "CachedToken[accessToken=[REDACTED], refreshAt=" + refreshAt + "]";
        }
    }

    private record ApprovalLink(String rel, String href) {
    }

    private record SafePayPalError(String name, String issue, String debugId) {
    }
}

