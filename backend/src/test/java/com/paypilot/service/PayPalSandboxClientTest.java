package com.paypilot.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypilot.config.PayPalConfig;
import com.paypilot.config.PayPalProperties;
import com.paypilot.dto.paypal.PayPalCaptureResult;
import com.paypilot.dto.paypal.PayPalCreateOrderRequest;
import com.paypilot.dto.paypal.PayPalOrderDetailsResult;
import com.paypilot.dto.paypal.PayPalOrderResult;
import com.paypilot.exception.PayPalApiException;
import com.paypilot.exception.PayPalAuthenticationException;
import com.paypilot.exception.PayPalConfigurationException;
import com.paypilot.exception.PayPalMalformedResponseException;
import com.paypilot.exception.PayPalUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PayPalSandboxClientTest {

    private static final String CANARY_CLIENT_ID = "CANARY_SANDBOX_CLIENT_ID_999";
    private static final String CANARY_CLIENT_SECRET = "CANARY_SANDBOX_CLIENT_SECRET_888";
    private static final String CANARY_BASIC_AUTH = Base64.getEncoder().encodeToString(
        (CANARY_CLIENT_ID + ":" + CANARY_CLIENT_SECRET).getBytes(StandardCharsets.UTF_8)
    );
    private static final String CANARY_TOKEN_1 = "CANARY_ACCESS_TOKEN_ONE_777";
    private static final String CANARY_TOKEN_2 = "CANARY_ACCESS_TOKEN_TWO_666";

    private PayPalProperties properties;
    private ObjectMapper objectMapper;
    private MutableClock clock;
    private MockRestServiceServer mockServer;
    private PayPalSandboxClient client;

    private Logger clientLogger;
    private Level previousLogLevel;
    private ListAppender<ILoggingEvent> logAppender;
    private final List<String> capturedExceptionMessages = new ArrayList<>();

    @BeforeEach
    void setUp() {
        properties = new PayPalProperties();
        properties.setClientId(CANARY_CLIENT_ID);
        properties.setClientSecret(CANARY_CLIENT_SECRET);
        properties.setMode("sandbox");
        properties.setBaseUrl("https://api-m.sandbox.paypal.com");
        properties.setTokenRefreshBufferSeconds(60L);

        objectMapper = new ObjectMapper();
        clock = new MutableClock(Instant.parse("2026-11-11T10:00:00Z"));

        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new PayPalSandboxClient(properties, builder, objectMapper, clock);

        clientLogger = (Logger) LoggerFactory.getLogger(PayPalSandboxClient.class);
        previousLogLevel = clientLogger.getLevel();
        clientLogger.setLevel(Level.DEBUG);
        logAppender = new ListAppender<>();
        logAppender.start();
        clientLogger.addAppender(logAppender);
        capturedExceptionMessages.clear();
    }

    @AfterEach
    void tearDown() {
        clientLogger.detachAppender(logAppender);
        clientLogger.setLevel(previousLogLevel);
        assertNoSensitiveDataExposed();
    }

    @Test
    void acquiresOAuthTokenReusesBeforeExpiryAndRefreshesShortlyBeforeExpiry() {
        mockOAuthTokenSuccess(CANARY_TOKEN_1, 3600);
        mockCreateOrderSuccess("ORD-1", "PAYER_ACTION_REQUIRED", "payer-action", "req-key-1", "750.00");

        PayPalOrderResult first = client.createOrder(sampleCreateRequest("req-key-1"));
        assertThat(first.paypalOrderId()).isEqualTo("ORD-1");
        verifyAndResetServer();

        // Advance 3539s (1s before the 60s pre-expiry refresh threshold): cached token must be reused
        clock.advanceSeconds(3539);
        mockCreateOrderSuccess("ORD-2", "PAYER_ACTION_REQUIRED", "payer-action", "req-key-2", "750.00");

        PayPalOrderResult second = client.createOrder(sampleCreateRequest("req-key-2"));
        assertThat(second.paypalOrderId()).isEqualTo("ORD-2");
        verifyAndResetServer();

        // Advance 1s to reach expiresIn - 60s boundary: client must refresh OAuth token
        clock.advanceSeconds(1);
        mockOAuthTokenSuccess(CANARY_TOKEN_2, 3600);
        mockCreateOrderSuccessWithToken(
            CANARY_TOKEN_2, "ORD-3", "PAYER_ACTION_REQUIRED", "payer-action", "req-key-3", "750.00"
        );

        PayPalOrderResult third = client.createOrder(sampleCreateRequest("req-key-3"));
        assertThat(third.paypalOrderId()).isEqualTo("ORD-3");
        mockServer.verify();
    }

    @Test
    void enforcesSandboxModeAndSandboxUrlFailingClosed() {
        for (String badMode : List.of("live", "production", "prod", "", "   ")) {
            PayPalProperties bad = validProperties();
            bad.setMode(badMode);
            assertThatThrownBy(() -> new PayPalSandboxClient(bad, RestClient.builder(), objectMapper, clock))
                .isInstanceOf(PayPalConfigurationException.class);
        }

        PayPalProperties nullMode = validProperties();
        nullMode.setMode(null);
        assertThatThrownBy(() -> new PayPalSandboxClient(nullMode, RestClient.builder(), objectMapper, clock))
            .isInstanceOf(PayPalConfigurationException.class);

        for (String badUrl : List.of(
                "https://api-m.paypal.com",
                "http://api-m.sandbox.paypal.com",
                "https://api-m.sandbox.paypal.com:8443",
                "https://api-m.sandbox.paypal.com/v2",
                "https://api-m.sandbox.paypal.com?foo=bar",
                "https://user@api-m.sandbox.paypal.com",
                "https://api-m.sandbox.paypal.com.evil.com",
                "",
                "   ")) {
            PayPalProperties bad = validProperties();
            bad.setBaseUrl(badUrl);
            assertThatThrownBy(() -> new PayPalSandboxClient(bad, RestClient.builder(), objectMapper, clock))
                .isInstanceOf(PayPalConfigurationException.class);
        }

        // Trailing slash canonicalizes cleanly when sandbox host & mode match
        PayPalProperties trailingSlash = validProperties();
        trailingSlash.setBaseUrl("https://api-m.sandbox.paypal.com/");
        assertThat(trailingSlash.canonicalBaseUrl()).isEqualTo("https://api-m.sandbox.paypal.com");
        assertThat(trailingSlash.toString()).doesNotContain(CANARY_CLIENT_SECRET);

        // PayPalConfig bean wiring also rejects live mode fail-closed
        PayPalConfig config = new PayPalConfig();
        assertThat(config.payPalRestClient(validProperties())).isNotNull();
        PayPalProperties live = validProperties();
        live.setMode("live");
        assertThatThrownBy(() -> config.payPalRestClient(live))
            .isInstanceOf(PayPalConfigurationException.class);
    }

    @Test
    void createsOrderWithExpectedStructureExactRequestIdAndExtractsPayerActionOrApproveUrl() {
        mockOAuthTokenSuccess(CANARY_TOKEN_1, 3600);

        String callerIdempotencyKey = "Inv-42_Attempt-Exact-Case_01";
        mockServer.expect(once(), requestTo("https://api-m.sandbox.paypal.com/v2/checkout/orders"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + CANARY_TOKEN_1))
            .andExpect(header("Prefer", "return=representation"))
            .andExpect(header("PayPal-Request-Id", callerIdempotencyKey))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.intent").value("CAPTURE"))
            .andExpect(jsonPath("$.purchase_units[0].reference_id").value("INV-20261111-AAA111"))
            .andExpect(jsonPath("$.purchase_units[0].invoice_id").value("INV-20261111-AAA111"))
            .andExpect(jsonPath("$.purchase_units[0].custom_id").value("inv-42-snap-abc"))
            .andExpect(jsonPath("$.purchase_units[0].description").value("Website development milestone"))
            .andExpect(jsonPath("$.purchase_units[0].amount.currency_code").value("USD"))
            .andExpect(jsonPath("$.purchase_units[0].amount.value").value("750.00"))
            .andExpect(jsonPath("$.payment_source.paypal.experience_context.user_action").value("PAY_NOW"))
            .andExpect(jsonPath("$.payment_source.paypal.experience_context.return_url")
                .value("http://localhost:5173/invoices/42/return"))
            .andExpect(jsonPath("$.payment_source.paypal.experience_context.cancel_url")
                .value("http://localhost:5173/invoices/42/cancel"))
            .andRespond(withStatus(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                    {
                      "id": "5O190127TN364715T",
                      "intent": "CAPTURE",
                      "status": "PAYER_ACTION_REQUIRED",
                      "create_time": "2026-11-11T10:00:00Z",
                      "purchase_units": [
                        {
                          "reference_id": "INV-20261111-AAA111",
                          "amount": { "currency_code": "USD", "value": "750.00" }
                        }
                      ],
                      "links": [
                        { "href": "https://api-m.sandbox.paypal.com/v2/checkout/orders/5O190127TN364715T", "rel": "self", "method": "GET" },
                        { "href": "https://www.sandbox.paypal.com/checkoutnow?token=5O190127TN364715T", "rel": "payer-action", "method": "GET" }
                      ]
                    }
                    """));

        PayPalOrderResult payerActionResult = client.createOrder(new PayPalCreateOrderRequest(
            callerIdempotencyKey,
            "INV-20261111-AAA111",
            "inv-42-snap-abc",
            "Website development milestone",
            new BigDecimal("750"),
            "usd",
            "http://localhost:5173/invoices/42/return",
            "http://localhost:5173/invoices/42/cancel"
        ));

        assertThat(payerActionResult.paypalOrderId()).isEqualTo("5O190127TN364715T");
        assertThat(payerActionResult.status()).isEqualTo("PAYER_ACTION_REQUIRED");
        assertThat(payerActionResult.approvalLinkRel()).isEqualTo("payer-action");
        assertThat(payerActionResult.approvalUrl())
            .isEqualTo("https://www.sandbox.paypal.com/checkoutnow?token=5O190127TN364715T");
        assertThat(payerActionResult.idempotencyKey()).isEqualTo(callerIdempotencyKey);
        assertThat(payerActionResult.amount()).isEqualByComparingTo("750.00");
        assertThat(payerActionResult.currency()).isEqualTo("USD");
        assertThat(payerActionResult.summary()).containsEntry("idempotencyKey", callerIdempotencyKey);
        verifyAndResetServer();

        // Compatibility fallback to "approve" link when "payer-action" is absent (token cached)
        mockCreateOrderSuccess("5O190127TN364715U", "CREATED", "approve", callerIdempotencyKey, "750.00");
        PayPalOrderResult approveFallbackResult = client.createOrder(sampleCreateRequest(callerIdempotencyKey));
        assertThat(approveFallbackResult.approvalLinkRel()).isEqualTo("approve");
        assertThat(approveFallbackResult.approvalUrl())
            .isEqualTo("https://www.sandbox.paypal.com/checkoutnow?token=5O190127TN364715U");

        mockServer.verify();
    }

    @Test
    void capturesOrderAndDistinguishesCompletedAndPendingResponses() {
        mockOAuthTokenSuccess(CANARY_TOKEN_1, 3600);

        // 1. COMPLETED capture
        mockServer.expect(once(), requestTo("https://api-m.sandbox.paypal.com/v2/checkout/orders/ORDER-COMP-1/capture"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + CANARY_TOKEN_1))
            .andExpect(header("Prefer", "return=representation"))
            .andExpect(header("PayPal-Request-Id", "cap-key-1"))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(content().string("{}"))
            .andRespond(withStatus(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                    {
                      "id": "ORDER-COMP-1",
                      "status": "COMPLETED",
                      "purchase_units": [
                        {
                          "reference_id": "INV-1",
                          "payments": {
                            "captures": [
                              {
                                "id": "CAP-COMP-99",
                                "status": "COMPLETED",
                                "final_capture": true,
                                "amount": { "currency_code": "USD", "value": "750.00" },
                                "create_time": "2026-11-11T10:01:00Z",
                                "update_time": "2026-11-11T10:01:01Z"
                              }
                            ]
                          }
                        }
                      ]
                    }
                    """));

        PayPalCaptureResult completed = client.captureOrder("ORDER-COMP-1", "cap-key-1");
        assertThat(completed.paypalOrderId()).isEqualTo("ORDER-COMP-1");
        assertThat(completed.orderStatus()).isEqualTo("COMPLETED");
        assertThat(completed.paypalCaptureId()).isEqualTo("CAP-COMP-99");
        assertThat(completed.captureStatus()).isEqualTo("COMPLETED");
        assertThat(completed.completed()).isTrue();
        assertThat(completed.pending()).isFalse();
        assertThat(completed.pendingReason()).isNull();
        assertThat(completed.amount()).isEqualByComparingTo("750.00");
        assertThat(completed.currency()).isEqualTo("USD");
        assertThat(completed.summary()).containsEntry("captureId", "CAP-COMP-99");
        verifyAndResetServer();

        // 2. PENDING capture even when outer order status is COMPLETED
        mockServer.expect(once(), requestTo("https://api-m.sandbox.paypal.com/v2/checkout/orders/ORDER-PEND-2/capture"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + CANARY_TOKEN_1))
            .andRespond(withStatus(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                    {
                      "id": "ORDER-PEND-2",
                      "status": "COMPLETED",
                      "purchase_units": [
                        {
                          "payments": {
                            "captures": [
                              {
                                "id": "CAP-PEND-55",
                                "status": "PENDING",
                                "status_details": { "reason": "PENDING_REVIEW" },
                                "amount": { "currency_code": "USD", "value": "750.00" },
                                "create_time": "2026-11-11T10:02:00Z"
                              }
                            ]
                          }
                        }
                      ]
                    }
                    """));

        PayPalCaptureResult pending = client.captureOrder("ORDER-PEND-2");
        assertThat(pending.paypalOrderId()).isEqualTo("ORDER-PEND-2");
        assertThat(pending.paypalCaptureId()).isEqualTo("CAP-PEND-55");
        assertThat(pending.captureStatus()).isEqualTo("PENDING");
        assertThat(pending.completed()).isFalse();
        assertThat(pending.pending()).isTrue();
        assertThat(pending.pendingReason()).isEqualTo("PENDING_REVIEW");
        assertThat(pending.summary()).containsEntry("pendingReason", "PENDING_REVIEW");
        verifyAndResetServer();

        // 3. DECLINED capture is neither completed nor pending
        mockServer.expect(once(), requestTo("https://api-m.sandbox.paypal.com/v2/checkout/orders/ORDER-DECL-3/capture"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                    {
                      "id": "ORDER-DECL-3",
                      "status": "COMPLETED",
                      "purchase_units": [
                        {
                          "payments": {
                            "captures": [
                              {
                                "id": "CAP-DECL-11",
                                "status": "DECLINED",
                                "status_details": { "reason": "CVV2_FAILURE" },
                                "amount": { "currency_code": "USD", "value": "750.00" }
                              }
                            ]
                          }
                        }
                      ]
                    }
                    """));

        PayPalCaptureResult declined = client.captureOrder("ORDER-DECL-3");
        assertThat(declined.captureStatus()).isEqualTo("DECLINED");
        assertThat(declined.completed()).isFalse();
        assertThat(declined.pending()).isFalse();
        assertThat(declined.pendingReason()).isEqualTo("CVV2_FAILURE");

        mockServer.verify();
    }

    @Test
    void handlesAuthenticationHttpErrorsTimeoutsAndMalformedResponsesWithoutLeakingSecrets() {
        // 1. Blank credentials fail closed before HTTP calls
        PayPalProperties missingCreds = validProperties();
        missingCreds.setClientSecret("");
        PayPalSandboxClient unconfiguredClient = new PayPalSandboxClient(
            missingCreds, RestClient.builder(), objectMapper, clock
        );
        captureFailure(() -> unconfiguredClient.createOrder(sampleCreateRequest("req-missing")),
            PayPalConfigurationException.class);

        // 2. OAuth 401 invalid_client failure
        mockServer.expect(once(), requestTo("https://api-m.sandbox.paypal.com/v1/oauth2/token"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"invalid_client\",\"error_description\":\"Client Authentication failed: "
                    + CANARY_CLIENT_SECRET + "\"}"));
        captureFailure(() -> client.createOrder(sampleCreateRequest("req-bad-oauth")),
            PayPalAuthenticationException.class);
        verifyAndResetServer();

        // 3. Successful OAuth, then 422 PayPal 4xx API failure
        mockOAuthTokenSuccess(CANARY_TOKEN_1, 3600);
        mockServer.expect(once(), requestTo("https://api-m.sandbox.paypal.com/v2/checkout/orders"))
            .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                    {
                      "name": "UNPROCESSABLE_ENTITY",
                      "debug_id": "dbg-422",
                      "details": [{ "issue": "CURRENCY_NOT_SUPPORTED", "description": "secret leak guard" }]
                    }
                    """));
        Throwable api422 = captureFailure(() -> client.createOrder(sampleCreateRequest("req-422")),
            PayPalApiException.class);
        assertThat(((PayPalApiException) api422).getHttpStatusCode()).isEqualTo(422);
        assertThat(((PayPalApiException) api422).getPaypalErrorIssue()).isEqualTo("CURRENCY_NOT_SUPPORTED");
        assertThat(((PayPalApiException) api422).getPaypalDebugId()).isEqualTo("dbg-422");
        verifyAndResetServer();

        // 4. 5xx error on capture
        mockServer.expect(once(), requestTo("https://api-m.sandbox.paypal.com/v2/checkout/orders/ORD-5XX/capture"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"name\":\"INTERNAL_SERVICE_ERROR\",\"debug_id\":\"dbg-503\"}"));
        Throwable api503 = captureFailure(() -> client.captureOrder("ORD-5XX"), PayPalApiException.class);
        assertThat(((PayPalApiException) api503).isServerError()).isTrue();
        verifyAndResetServer();

        // 5. Network timeout (SocketTimeoutException wrapped into ResourceAccessException by RestClient)
        mockServer.expect(once(), requestTo("https://api-m.sandbox.paypal.com/v2/checkout/orders/ORD-TIME/capture"))
            .andRespond(request -> {
                throw new SocketTimeoutException("Read timed out");
            });
        captureFailure(() -> client.captureOrder("ORD-TIME"), PayPalUnavailableException.class);
        verifyAndResetServer();

        // 6. Missing approval link
        mockServer.expect(once(), requestTo("https://api-m.sandbox.paypal.com/v2/checkout/orders"))
            .andRespond(withStatus(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                    {
                      "id": "ORD-NOLINK",
                      "status": "CREATED",
                      "links": [{ "href": "https://api-m.sandbox.paypal.com/v2/checkout/orders/ORD-NOLINK", "rel": "self" }]
                    }
                    """));
        captureFailure(() -> client.createOrder(sampleCreateRequest("req-nolink")),
            PayPalMalformedResponseException.class);
        verifyAndResetServer();

        // 7. Mismatched amount returned by PayPal fails closed
        mockCreateOrderSuccess("ORD-MISMATCH", "PAYER_ACTION_REQUIRED", "payer-action", "req-mismatch", "999.00");
        captureFailure(() -> client.createOrder(sampleCreateRequest("req-mismatch")),
            PayPalMalformedResponseException.class);
        verifyAndResetServer();

        // 8. Malformed/incomplete capture and unrecognized status never become COMPLETED
        mockServer.expect(once(), requestTo("https://api-m.sandbox.paypal.com/v2/checkout/orders/ORD-BOGUS/capture"))
            .andRespond(withStatus(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                    {
                      "id": "ORD-BOGUS",
                      "status": "COMPLETED",
                      "purchase_units": [{
                        "payments": {
                          "captures": [{
                            "id": "CAP-BOGUS",
                            "status": "UNRECOGNIZED_STATUS",
                            "amount": { "currency_code": "USD", "value": "750.00" }
                          }]
                        }
                      }]
                    }
                    """));
        captureFailure(() -> client.captureOrder("ORD-BOGUS"), PayPalMalformedResponseException.class);
        verifyAndResetServer();

        mockServer.expect(once(), requestTo("https://api-m.sandbox.paypal.com/v2/checkout/orders/ORD-BADJSON/capture"))
            .andRespond(withSuccess("{not-json", MediaType.APPLICATION_JSON));
        captureFailure(() -> client.captureOrder("ORD-BADJSON"), PayPalMalformedResponseException.class);
        verifyAndResetServer();

        // 9. API 401 evicts cached token so subsequent call re-acquires OAuth token
        mockServer.expect(once(), requestTo("https://api-m.sandbox.paypal.com/v2/checkout/orders/ORD-EXPIRED/capture"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"name\":\"AUTHENTICATION_FAILURE\",\"debug_id\":\"dbg-401\"}"));
        captureFailure(() -> client.captureOrder("ORD-EXPIRED"), PayPalAuthenticationException.class);
        verifyAndResetServer();

        mockOAuthTokenSuccess(CANARY_TOKEN_2, 3600);
        mockCreateOrderSuccessWithToken(
            CANARY_TOKEN_2, "ORD-REAUTH", "PAYER_ACTION_REQUIRED", "payer-action", "req-reauth", "750.00"
        );
        PayPalOrderResult recovered = client.createOrder(sampleCreateRequest("req-reauth"));
        assertThat(recovered.paypalOrderId()).isEqualTo("ORD-REAUTH");

        mockServer.verify();
    }

    @Test
    void fetchesOrderDetailsForPendingVerificationWithoutRepeatingCapture() {
        mockOAuthTokenSuccess(CANARY_TOKEN_1, 3600);

        // 1. Order with capture that settled to COMPLETED
        mockServer.expect(once(), requestTo("https://api-m.sandbox.paypal.com/v2/checkout/orders/ORD-SETTLED-1"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + CANARY_TOKEN_1))
            .andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
            .andRespond(withSuccess("""
                {
                  "id": "ORD-SETTLED-1",
                  "status": "COMPLETED",
                  "purchase_units": [{
                    "amount": { "currency_code": "USD", "value": "750.00" },
                    "payments": {
                      "captures": [{
                        "id": "CAP-SETTLED-1",
                        "status": "COMPLETED",
                        "amount": { "currency_code": "USD", "value": "750.00" },
                        "update_time": "2026-11-11T10:15:00Z"
                      }]
                    }
                  }]
                }
                """, MediaType.APPLICATION_JSON));

        PayPalOrderDetailsResult settled = client.getOrderDetails("ORD-SETTLED-1");
        assertThat(settled.paypalOrderId()).isEqualTo("ORD-SETTLED-1");
        assertThat(settled.orderStatus()).isEqualTo("COMPLETED");
        assertThat(settled.resolveCapture("CAP-SETTLED-1")).isPresent()
            .get()
            .satisfies(c -> {
                assertThat(c.completed()).isTrue();
                assertThat(c.pending()).isFalse();
                assertThat(c.amount()).isEqualByComparingTo("750.00");
                assertThat(c.currency()).isEqualTo("USD");
            });
        verifyAndResetServer();

        // 2. Order with capture still PENDING and approved order without captures yet
        mockServer.expect(once(), requestTo("https://api-m.sandbox.paypal.com/v2/checkout/orders/ORD-STILL-PEND"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("""
                {
                  "id": "ORD-STILL-PEND",
                  "status": "COMPLETED",
                  "purchase_units": [{
                    "amount": { "currency_code": "USD", "value": "750.00" },
                    "payments": {
                      "captures": [{
                        "id": "CAP-PEND-9",
                        "status": "PENDING",
                        "status_details": { "reason": "ECHECK" },
                        "amount": { "currency_code": "USD", "value": "750.00" }
                      }]
                    }
                  }]
                }
                """, MediaType.APPLICATION_JSON));

        PayPalOrderDetailsResult stillPending = client.getOrderDetails("ORD-STILL-PEND");
        assertThat(stillPending.resolveCapture("CAP-PEND-9")).isPresent()
            .get()
            .satisfies(c -> {
                assertThat(c.completed()).isFalse();
                assertThat(c.pending()).isTrue();
                assertThat(c.pendingReason()).isEqualTo("ECHECK");
            });
        verifyAndResetServer();

        // 3. Mismatched order ID fails closed
        mockServer.expect(once(), requestTo("https://api-m.sandbox.paypal.com/v2/checkout/orders/ORD-CHECK-1"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("{\"id\":\"ORD-DIFFERENT\",\"status\":\"CREATED\"}", MediaType.APPLICATION_JSON));
        captureFailure(() -> client.getOrderDetails("ORD-CHECK-1"), PayPalMalformedResponseException.class);

        mockServer.verify();
    }

    private void verifyAndResetServer() {
        mockServer.verify();
        mockServer.reset();
    }

    private Throwable captureFailure(Runnable action, Class<? extends Throwable> expectedType) {
        final Throwable[] thrown = new Throwable[1];
        assertThatThrownBy(() -> {
            try {
                action.run();
            } catch (Throwable ex) {
                thrown[0] = ex;
                throw ex;
            }
        }).isInstanceOf(expectedType);
        if (thrown[0] != null && thrown[0].getMessage() != null) {
            capturedExceptionMessages.add(thrown[0].getMessage());
        }
        return thrown[0];
    }

    private void assertNoSensitiveDataExposed() {
        List<String> sensitiveValues = List.of(
            CANARY_CLIENT_ID,
            CANARY_CLIENT_SECRET,
            CANARY_BASIC_AUTH,
            CANARY_TOKEN_1,
            CANARY_TOKEN_2
        );

        for (ILoggingEvent event : logAppender.list) {
            String message = event.getFormattedMessage();
            for (String secret : sensitiveValues) {
                assertThat(message)
                    .as("Logged message must never expose secret material")
                    .doesNotContain(secret);
            }
        }

        for (String message : capturedExceptionMessages) {
            for (String secret : sensitiveValues) {
                assertThat(message)
                    .as("Exception message must never expose secret material")
                    .doesNotContain(secret);
            }
        }
    }

    private void mockOAuthTokenSuccess(String token, long expiresInSeconds) {
        mockServer.expect(once(), requestTo("https://api-m.sandbox.paypal.com/v1/oauth2/token"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Basic " + CANARY_BASIC_AUTH))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
            .andExpect(content().string(containsString("grant_type=client_credentials")))
            .andRespond(withSuccess(
                "{\"access_token\":\"" + token + "\",\"token_type\":\"Bearer\",\"expires_in\":"
                    + expiresInSeconds + "}",
                MediaType.APPLICATION_JSON
            ));
    }

    private void mockCreateOrderSuccess(
            String orderId,
            String status,
            String linkRel,
            String idempotencyKey,
            String amount) {
        mockCreateOrderSuccessWithToken(CANARY_TOKEN_1, orderId, status, linkRel, idempotencyKey, amount);
    }

    private void mockCreateOrderSuccessWithToken(
            String bearerToken,
            String orderId,
            String status,
            String linkRel,
            String idempotencyKey,
            String amount) {
        mockServer.expect(once(), requestTo("https://api-m.sandbox.paypal.com/v2/checkout/orders"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken))
            .andExpect(header("PayPal-Request-Id", idempotencyKey))
            .andRespond(withStatus(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                    {
                      "id": "%s",
                      "status": "%s",
                      "purchase_units": [{ "amount": { "currency_code": "USD", "value": "%s" } }],
                      "links": [{
                        "href": "https://www.sandbox.paypal.com/checkoutnow?token=%s",
                        "rel": "%s",
                        "method": "GET"
                      }]
                    }
                    """.formatted(orderId, status, amount, orderId, linkRel)));
    }

    private PayPalCreateOrderRequest sampleCreateRequest(String idempotencyKey) {
        return new PayPalCreateOrderRequest(
            idempotencyKey,
            "INV-20261111-AAA111",
            "inv-42-snap-abc",
            "Website development milestone",
            new BigDecimal("750.00"),
            "USD",
            "http://localhost:5173/invoices/42/return",
            "http://localhost:5173/invoices/42/cancel"
        );
    }

    private PayPalProperties validProperties() {
        PayPalProperties props = new PayPalProperties();
        props.setClientId(CANARY_CLIENT_ID);
        props.setClientSecret(CANARY_CLIENT_SECRET);
        props.setMode("sandbox");
        props.setBaseUrl("https://api-m.sandbox.paypal.com");
        return props;
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current;

        private MutableClock(Instant initial) {
            this.current = new AtomicReference<>(initial);
        }

        void advanceSeconds(long seconds) {
            current.updateAndGet(now -> now.plusSeconds(seconds));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}
