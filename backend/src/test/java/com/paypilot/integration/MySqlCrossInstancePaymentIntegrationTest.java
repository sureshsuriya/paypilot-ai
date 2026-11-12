package com.paypilot.integration;

import com.paypilot.PayPilotApplication;
import com.paypilot.dto.invoice.ApproveInvoiceRequest;
import com.paypilot.dto.invoice.CreateInvoiceRequest;
import com.paypilot.dto.invoice.InvoiceResponse;
import com.paypilot.dto.invoice.UpdateInvoiceRequest;
import com.paypilot.dto.payment.CancelPaymentRequest;
import com.paypilot.dto.payment.CreatePaymentOrderRequest;
import com.paypilot.dto.payment.PaymentResponse;
import com.paypilot.dto.paypal.PayPalCaptureResult;
import com.paypilot.dto.paypal.PayPalCreateOrderRequest;
import com.paypilot.dto.paypal.PayPalOrderDetailsResult;
import com.paypilot.dto.paypal.PayPalOrderResult;
import com.paypilot.exception.InvalidInvoiceStateException;
import com.paypilot.exception.PayPalApiException;
import com.paypilot.exception.PayPalUnavailableException;
import com.paypilot.model.Invoice;
import com.paypilot.model.InvoiceStatus;
import com.paypilot.model.Payment;
import com.paypilot.model.PaymentOrderIntent;
import com.paypilot.model.PaymentOrderIntentStatus;
import com.paypilot.model.PaymentStatus;
import com.paypilot.model.User;
import com.paypilot.model.UserRole;
import com.paypilot.repository.AuditLogRepository;
import com.paypilot.repository.CustomerRepository;
import com.paypilot.repository.InvoiceRepository;
import com.paypilot.repository.PaymentLinkRepository;
import com.paypilot.repository.PaymentOrderIntentRepository;
import com.paypilot.repository.PaymentRepository;
import com.paypilot.repository.UserRepository;
import com.paypilot.service.InvoiceService;
import com.paypilot.service.PayPalSandboxClient;
import com.paypilot.service.PaymentService;
import com.paypilot.service.PaymentService.PaymentOrderOutcome;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.MapPropertySource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Multi-instance integration test executed against an isolated real MySQL 8.0 schema
 * (paypilot_test_mysql) with two completely separate Spring Boot application contexts
 * and Hikari connection pools.
 */
class MySqlCrossInstancePaymentIntegrationTest {

    private static final String TEST_SCHEMA = "paypilot_test_mysql";
    private static final String LOOPBACK_IP = "127.0.0.1";

    private static String adminServerUrl;
    private static String testDbUrl;
    private static String dbUser;
    private static String dbPassword;

    private static ConfigurableApplicationContext contextA;
    private static ConfigurableApplicationContext contextB;

    private static PaymentService paymentServiceA;
    private static PaymentService paymentServiceB;
    private static InvoiceService invoiceServiceA;
    private static InvoiceService invoiceServiceB;
    private static PayPalSandboxClient clientA;
    private static PayPalSandboxClient clientB;

    private static UserRepository userRepoA;
    private static CustomerRepository customerRepoA;
    private static InvoiceRepository invoiceRepoA;
    private static PaymentLinkRepository paymentLinkRepoA;
    private static PaymentRepository paymentRepoA;
    private static PaymentOrderIntentRepository intentRepoA;
    private static AuditLogRepository auditRepoA;
    private static JdbcTemplate jdbcB;

    @BeforeAll
    static void startTwoIndependentMySqlInstances() throws Exception {
        Map<String, String> env = loadDatabaseCredentials();
        String baseUrl = env.getOrDefault(
            "SPRING_DATASOURCE_URL",
            "jdbc:mysql://localhost:3306/paypilot_ai?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true"
        );
        dbUser = requireValue(env, "SPRING_DATASOURCE_USERNAME");
        dbPassword = requireValue(env, "SPRING_DATASOURCE_PASSWORD");

        int qIdx = baseUrl.indexOf('?');
        String query = qIdx >= 0 ? baseUrl.substring(qIdx) : "?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true";
        String pathPart = qIdx >= 0 ? baseUrl.substring(0, qIdx) : baseUrl;
        int lastSlash = pathPart.lastIndexOf('/');
        String hostPrefix = pathPart.substring(0, lastSlash + 1);

        adminServerUrl = hostPrefix + query;
        testDbUrl = hostPrefix + TEST_SCHEMA + query;
        assertThat(testDbUrl).doesNotContain("paypilot_ai");

        try (Connection conn = DriverManager.getConnection(adminServerUrl, dbUser, dbPassword);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE IF NOT EXISTS `" + TEST_SCHEMA
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }

        Flyway flyway = Flyway.configure()
            .dataSource(testDbUrl, dbUser, dbPassword)
            .locations("classpath:db/migration")
            .cleanDisabled(false)
            .load();
        flyway.clean();
        flyway.migrate();

        contextA = launchInstanceContext();
        contextB = launchInstanceContext();

        verifyConnectedToIsolatedRealMySql(contextA);
        verifyConnectedToIsolatedRealMySql(contextB);

        paymentServiceA = contextA.getBean(PaymentService.class);
        paymentServiceB = contextB.getBean(PaymentService.class);
        invoiceServiceA = contextA.getBean(InvoiceService.class);
        invoiceServiceB = contextB.getBean(InvoiceService.class);
        clientA = contextA.getBean(PayPalSandboxClient.class);
        clientB = contextB.getBean(PayPalSandboxClient.class);

        userRepoA = contextA.getBean(UserRepository.class);
        customerRepoA = contextA.getBean(CustomerRepository.class);
        invoiceRepoA = contextA.getBean(InvoiceRepository.class);
        paymentLinkRepoA = contextA.getBean(PaymentLinkRepository.class);
        paymentRepoA = contextA.getBean(PaymentRepository.class);
        intentRepoA = contextA.getBean(PaymentOrderIntentRepository.class);
        auditRepoA = contextA.getBean(AuditLogRepository.class);
        jdbcB = contextB.getBean(JdbcTemplate.class);
    }

    @AfterAll
    static void stopContextsAndDropIsolatedSchema() throws Exception {
        if (contextA != null) {
            contextA.close();
        }
        if (contextB != null) {
            contextB.close();
        }
        if (adminServerUrl != null) {
            try (Connection conn = DriverManager.getConnection(adminServerUrl, dbUser, dbPassword);
                 Statement stmt = conn.createStatement()) {
                stmt.execute("DROP DATABASE IF EXISTS `" + TEST_SCHEMA + "`");
            }
        }
    }

    @BeforeEach
    void resetStateAndMocks() {
        Mockito.reset(clientA, clientB);
        auditRepoA.deleteAll();
        paymentRepoA.deleteAll();
        intentRepoA.deleteAll();
        paymentLinkRepoA.deleteAll();
        invoiceRepoA.deleteAll();
        customerRepoA.deleteAll();
        userRepoA.deleteAll();
    }

    @Test
    void concurrentOrderCreationAcrossTwoInstancesIsAtomicallySerializedByMySqlLease() throws Exception {
        User owner = seedUser("mysql.order@paypilot.test");
        InvoiceResponse approved = createAndApproveInvoice(owner.getId(), new BigDecimal("750.00"), "USD");

        CountDownLatch enteredPayPalA = new CountDownLatch(1);
        CountDownLatch releasePayPalA = new CountDownLatch(1);
        doAnswer(inv -> {
            PayPalCreateOrderRequest req = inv.getArgument(0);
            enteredPayPalA.countDown();
            assertThat(releasePayPalA.await(10, TimeUnit.SECONDS)).isTrue();
            return new PayPalOrderResult(
                "ORD-MYSQL-CONC-1",
                "PAYER_ACTION_REQUIRED",
                "https://www.sandbox.paypal.com/checkoutnow?token=ORD-MYSQL-CONC-1",
                "payer-action",
                req.idempotencyKey(),
                req.amount(),
                req.currency(),
                Map.of("orderId", "ORD-MYSQL-CONC-1", "status", "PAYER_ACTION_REQUIRED")
            );
        }).when(clientA).createOrder(any(PayPalCreateOrderRequest.class));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<PaymentOrderOutcome> futureA = pool.submit(() -> paymentServiceA.createOrder(
                owner.getId(),
                new CreatePaymentOrderRequest(approved.id(), null, null),
                LOOPBACK_IP
            ));

            assertThat(enteredPayPalA.await(10, TimeUnit.SECONDS)).isTrue();

            // Verify MySQL row holds an active atomic lease while HTTP call is outside any DB tx
            String lockOwner = jdbcB.queryForObject(
                "SELECT payment_lock_owner FROM invoices WHERE id = ?", String.class, approved.id()
            );
            assertThat(lockOwner).isNotBlank();

            // Separate Spring context B cannot acquire lease or call PayPal concurrently
            assertThatThrownBy(() -> paymentServiceB.createOrder(
                owner.getId(),
                new CreatePaymentOrderRequest(approved.id(), null, null),
                LOOPBACK_IP
            ))
                .isInstanceOf(InvalidInvoiceStateException.class)
                .hasMessageContaining("in progress");
            verifyNoInteractions(clientB);

            releasePayPalA.countDown();
            PaymentOrderOutcome outcomeA = futureA.get(10, TimeUnit.SECONDS);
            assertThat(outcomeA.reused()).isFalse();
            assertThat(outcomeA.response().paypalOrderId()).isEqualTo("ORD-MYSQL-CONC-1");

            // Lease cleared in MySQL cleanly; Instance B retry now reuses existing order
            String lockOwnerAfter = jdbcB.queryForObject(
                "SELECT payment_lock_owner FROM invoices WHERE id = ?", String.class, approved.id()
            );
            assertThat(lockOwnerAfter).isNull();

            PaymentOrderOutcome outcomeB = paymentServiceB.createOrder(
                owner.getId(),
                new CreatePaymentOrderRequest(approved.id(), null, null),
                LOOPBACK_IP
            );
            assertThat(outcomeB.reused()).isTrue();
            assertThat(outcomeB.response().paypalOrderId()).isEqualTo("ORD-MYSQL-CONC-1");

            verify(clientA, times(1)).createOrder(any());
            verifyNoInteractions(clientB);
            assertThat(paymentRepoA.findByInvoiceIdOrderByCreatedAtDesc(approved.id())).hasSize(1);
        } finally {
            releasePayPalA.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentCaptureBlocksParallelCaptureCancelAndInvoiceMutationOnMySql() throws Exception {
        User owner = seedUser("mysql.capture@paypilot.test");
        InvoiceResponse approved = createAndApproveInvoice(owner.getId(), new BigDecimal("920.00"), "USD");

        mockOrderCreateSuccess(clientA, "ORD-MYSQL-CAP-1", new BigDecimal("920.00"), "USD");
        paymentServiceA.createOrder(owner.getId(), new CreatePaymentOrderRequest(approved.id(), null, null), LOOPBACK_IP);

        CountDownLatch enteredCaptureA = new CountDownLatch(1);
        CountDownLatch releaseCaptureA = new CountDownLatch(1);
        doAnswer(inv -> {
            enteredCaptureA.countDown();
            assertThat(releaseCaptureA.await(10, TimeUnit.SECONDS)).isTrue();
            return new PayPalCaptureResult(
                "ORD-MYSQL-CAP-1", "COMPLETED", "CAP-MYSQL-CAP-1", "COMPLETED",
                true, false, null,
                new BigDecimal("920.00"), "USD", "2026-11-11T13:00:00Z",
                Map.of("orderId", "ORD-MYSQL-CAP-1", "captureId", "CAP-MYSQL-CAP-1", "captureStatus", "COMPLETED")
            );
        }).when(clientA).captureOrder(eq("ORD-MYSQL-CAP-1"), anyString());

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<PaymentResponse> captureFutureA = pool.submit(() ->
                paymentServiceA.captureOrder(owner.getId(), "ORD-MYSQL-CAP-1", LOOPBACK_IP)
            );
            assertThat(enteredCaptureA.await(10, TimeUnit.SECONDS)).isTrue();

            // Parallel instance B capture, cancel, edit, and revoke are all blocked atomically in MySQL
            assertThatThrownBy(() -> paymentServiceB.captureOrder(owner.getId(), "ORD-MYSQL-CAP-1", LOOPBACK_IP))
                .isInstanceOf(InvalidInvoiceStateException.class);
            assertThatThrownBy(() -> paymentServiceB.cancelOrder(
                owner.getId(), "ORD-MYSQL-CAP-1", new CancelPaymentRequest("concurrent cancel"), LOOPBACK_IP))
                .isInstanceOf(InvalidInvoiceStateException.class);
            assertThatThrownBy(() -> invoiceServiceB.updateInvoice(
                owner.getId(),
                approved.id(),
                new UpdateInvoiceRequest(
                    "Client X", "x@test.com", "Edited", new BigDecimal("100.00"),
                    "USD", LocalDate.now().plusDays(20)
                ),
                LOOPBACK_IP
            )).isInstanceOf(InvalidInvoiceStateException.class);
            assertThatThrownBy(() -> invoiceServiceB.revokeApproval(owner.getId(), approved.id(), LOOPBACK_IP))
                .isInstanceOf(InvalidInvoiceStateException.class);

            verifyNoInteractions(clientB);

            releaseCaptureA.countDown();
            PaymentResponse captured = captureFutureA.get(10, TimeUnit.SECONDS);
            assertThat(captured.status()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(captured.invoiceStatus()).isEqualTo(InvoiceStatus.PAID);

            // Instance B capture retry is idempotent replay without calling clientB
            PaymentResponse replayB = paymentServiceB.captureOrder(owner.getId(), "ORD-MYSQL-CAP-1", LOOPBACK_IP);
            assertThat(replayB.reused()).isTrue();
            assertThat(replayB.status()).isEqualTo(PaymentStatus.CAPTURED);
            verifyNoInteractions(clientB);
        } finally {
            releaseCaptureA.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void recoversCrashExpiredLeasesLostResponsesAndAlreadyCapturedOnRealMySql() {
        User owner = seedUser("mysql.recover@paypilot.test");

        // --- 1. Order created on PayPal, backend crashes with expired lease -> Instance B reuses same key ---
        InvoiceResponse inv1 = createAndApproveInvoice(owner.getId(), new BigDecimal("410.00"), "USD");
        AtomicReference<String> firstOrderKey = new AtomicReference<>();
        when(clientA.createOrder(any(PayPalCreateOrderRequest.class))).thenAnswer(inv -> {
            PayPalCreateOrderRequest req = inv.getArgument(0);
            firstOrderKey.set(req.idempotencyKey());
            throw new PayPalUnavailableException("Simulated network loss after order creation.");
        });

        assertThatThrownBy(() -> paymentServiceA.createOrder(
            owner.getId(), new CreatePaymentOrderRequest(inv1.id(), null, null), LOOPBACK_IP
        )).isInstanceOf(PayPalUnavailableException.class);

        // Simulate un-released crashed lock whose TTL has expired in MySQL
        expireLeaseViaHibernate(inv1.id());

        when(clientB.createOrder(any(PayPalCreateOrderRequest.class))).thenAnswer(inv -> {
            PayPalCreateOrderRequest req = inv.getArgument(0);
            assertThat(req.idempotencyKey()).isEqualTo(firstOrderKey.get());
            return new PayPalOrderResult(
                "ORD-MYSQL-REC-1", "PAYER_ACTION_REQUIRED",
                "https://www.sandbox.paypal.com/checkoutnow?token=ORD-MYSQL-REC-1",
                "payer-action", req.idempotencyKey(), req.amount(), req.currency(),
                Map.of("orderId", "ORD-MYSQL-REC-1", "status", "PAYER_ACTION_REQUIRED")
            );
        });

        PaymentOrderOutcome recoveredOrder = paymentServiceB.createOrder(
            owner.getId(), new CreatePaymentOrderRequest(inv1.id(), null, null), LOOPBACK_IP
        );
        assertThat(recoveredOrder.response().paypalOrderId()).isEqualTo("ORD-MYSQL-REC-1");
        assertThat(intentRepoA.findByInvoiceIdOrderByCreatedAtDesc(inv1.id())).singleElement()
            .satisfies(i -> {
                assertThat(i.getStatus()).isEqualTo(PaymentOrderIntentStatus.COMPLETED);
                assertThat(i.getActiveInvoiceId()).isNull();
            });

        // --- 2. Capture timeout when PayPal did NOT capture -> GET APPROVED -> safe retry with original key ---
        AtomicReference<String> capturedKeyRef = new AtomicReference<>();
        when(clientA.captureOrder(eq("ORD-MYSQL-REC-1"), anyString())).thenAnswer(inv -> {
            capturedKeyRef.set(inv.getArgument(1));
            throw new PayPalUnavailableException("Timeout before capture reached PayPal.");
        });
        assertThatThrownBy(() -> paymentServiceA.captureOrder(owner.getId(), "ORD-MYSQL-REC-1", LOOPBACK_IP))
            .isInstanceOf(PayPalUnavailableException.class);

        // Simulate expired crashed lease; verify cancel and invoice edits remain blocked
        expireLeaseViaHibernate(inv1.id());
        assertThatThrownBy(() -> paymentServiceB.cancelOrder(
            owner.getId(), "ORD-MYSQL-REC-1", new CancelPaymentRequest("too late"), LOOPBACK_IP))
            .isInstanceOf(InvalidInvoiceStateException.class);
        assertThatThrownBy(() -> invoiceServiceB.updateInvoice(
            owner.getId(),
            inv1.id(),
            new UpdateInvoiceRequest("Acme", "a@b.test", "Desc", new BigDecimal("410.00"), "USD", LocalDate.now().plusDays(15)),
            LOOPBACK_IP
        )).isInstanceOf(InvalidInvoiceStateException.class);

        when(clientB.getOrderDetails("ORD-MYSQL-REC-1")).thenReturn(
            new PayPalOrderDetailsResult("ORD-MYSQL-REC-1", "APPROVED", new BigDecimal("410.00"), "USD", List.of())
        );
        when(clientB.captureOrder(eq("ORD-MYSQL-REC-1"), eq(capturedKeyRef.get()))).thenReturn(
            new PayPalCaptureResult(
                "ORD-MYSQL-REC-1", "COMPLETED", "CAP-MYSQL-REC-1", "COMPLETED",
                true, false, null, new BigDecimal("410.00"), "USD", "2026-11-11T13:10:00Z",
                Map.of("orderId", "ORD-MYSQL-REC-1", "captureId", "CAP-MYSQL-REC-1", "captureStatus", "COMPLETED")
            )
        );

        PaymentResponse safeRetryCapture = paymentServiceB.captureOrder(owner.getId(), "ORD-MYSQL-REC-1", LOOPBACK_IP);
        assertThat(safeRetryCapture.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(safeRetryCapture.invoiceStatus()).isEqualTo(InvoiceStatus.PAID);
        verify(clientB, times(1)).getOrderDetails("ORD-MYSQL-REC-1");
        verify(clientB, times(1)).captureOrder("ORD-MYSQL-REC-1", capturedKeyRef.get());

        // --- 3. Capture succeeded on PayPal before response lost -> retry via GET does NOT re-capture ---
        InvoiceResponse inv2 = createAndApproveInvoice(owner.getId(), new BigDecimal("525.00"), "USD");
        mockOrderCreateSuccess(clientA, "ORD-MYSQL-REC-2", new BigDecimal("525.00"), "USD");
        paymentServiceA.createOrder(owner.getId(), new CreatePaymentOrderRequest(inv2.id(), null, null), LOOPBACK_IP);

        when(clientA.captureOrder(eq("ORD-MYSQL-REC-2"), anyString()))
            .thenThrow(new PayPalUnavailableException("Socket closed after PayPal captured payment."));
        assertThatThrownBy(() -> paymentServiceA.captureOrder(owner.getId(), "ORD-MYSQL-REC-2", LOOPBACK_IP))
            .isInstanceOf(PayPalUnavailableException.class);

        when(clientB.getOrderDetails("ORD-MYSQL-REC-2")).thenReturn(new PayPalOrderDetailsResult(
            "ORD-MYSQL-REC-2", "COMPLETED", new BigDecimal("525.00"), "USD",
            List.of(new PayPalCaptureResult(
                "ORD-MYSQL-REC-2", "COMPLETED", "CAP-MYSQL-REC-2", "COMPLETED",
                true, false, null, new BigDecimal("525.00"), "USD", "2026-11-11T13:12:00Z",
                Map.of("orderId", "ORD-MYSQL-REC-2", "captureId", "CAP-MYSQL-REC-2", "captureStatus", "COMPLETED")
            ))
        ));

        PaymentResponse recoveredPaid = paymentServiceB.captureOrder(owner.getId(), "ORD-MYSQL-REC-2", LOOPBACK_IP);
        assertThat(recoveredPaid.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(recoveredPaid.invoiceStatus()).isEqualTo(InvoiceStatus.PAID);
        verify(clientB, times(1)).getOrderDetails("ORD-MYSQL-REC-2");
        verify(clientB, never()).captureOrder(eq("ORD-MYSQL-REC-2"), anyString());

        // --- 4. ORDER_ALREADY_CAPTURED triggers GET verification ---
        InvoiceResponse inv3 = createAndApproveInvoice(owner.getId(), new BigDecimal("215.00"), "USD");
        mockOrderCreateSuccess(clientA, "ORD-MYSQL-OAC-3", new BigDecimal("215.00"), "USD");
        paymentServiceA.createOrder(owner.getId(), new CreatePaymentOrderRequest(inv3.id(), null, null), LOOPBACK_IP);

        when(clientA.captureOrder(eq("ORD-MYSQL-OAC-3"), anyString())).thenThrow(
            new PayPalApiException(422, "UNPROCESSABLE_ENTITY", "ORDER_ALREADY_CAPTURED", "dbg", "Already captured")
        );
        when(clientA.getOrderDetails("ORD-MYSQL-OAC-3")).thenReturn(new PayPalOrderDetailsResult(
            "ORD-MYSQL-OAC-3", "COMPLETED", new BigDecimal("215.00"), "USD",
            List.of(new PayPalCaptureResult(
                "ORD-MYSQL-OAC-3", "COMPLETED", "CAP-MYSQL-OAC-3", "COMPLETED",
                true, false, null, new BigDecimal("215.00"), "USD", "2026-11-11T13:15:00Z",
                Map.of("orderId", "ORD-MYSQL-OAC-3", "captureId", "CAP-MYSQL-OAC-3", "captureStatus", "COMPLETED")
            ))
        ));

        PaymentResponse recoveredOac = paymentServiceA.captureOrder(owner.getId(), "ORD-MYSQL-OAC-3", LOOPBACK_IP);
        assertThat(recoveredOac.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(recoveredOac.invoiceStatus()).isEqualTo(InvoiceStatus.PAID);

        // --- 5. PENDING capture later resolving to COMPLETED vs FAILED ---
        InvoiceResponse inv4 = createAndApproveInvoice(owner.getId(), new BigDecimal("260.00"), "USD");
        mockOrderCreateSuccess(clientA, "ORD-MYSQL-PEND-4", new BigDecimal("260.00"), "USD");
        paymentServiceA.createOrder(owner.getId(), new CreatePaymentOrderRequest(inv4.id(), null, null), LOOPBACK_IP);

        when(clientA.captureOrder(eq("ORD-MYSQL-PEND-4"), anyString())).thenReturn(
            new PayPalCaptureResult(
                "ORD-MYSQL-PEND-4", "COMPLETED", "CAP-PEND-M4", "PENDING",
                false, true, "PENDING_REVIEW",
                new BigDecimal("260.00"), "USD", null,
                Map.of("captureId", "CAP-PEND-M4", "captureStatus", "PENDING", "pendingReason", "PENDING_REVIEW")
            )
        );
        PaymentResponse initialPending = paymentServiceA.captureOrder(owner.getId(), "ORD-MYSQL-PEND-4", LOOPBACK_IP);
        assertThat(initialPending.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(initialPending.invoiceStatus()).isEqualTo(InvoiceStatus.APPROVED);

        when(clientB.getOrderDetails("ORD-MYSQL-PEND-4")).thenReturn(
            new PayPalOrderDetailsResult(
                "ORD-MYSQL-PEND-4", "COMPLETED", new BigDecimal("260.00"), "USD",
                List.of(new PayPalCaptureResult(
                    "ORD-MYSQL-PEND-4", "COMPLETED", "CAP-PEND-M4", "DECLINED",
                    false, false, "ECHECK_BOUNCED",
                    new BigDecimal("260.00"), "USD", null,
                    Map.of("captureId", "CAP-PEND-M4", "captureStatus", "DECLINED", "pendingReason", "ECHECK_BOUNCED")
                ))
            )
        );
        assertThatThrownBy(() -> paymentServiceB.captureOrder(owner.getId(), "ORD-MYSQL-PEND-4", LOOPBACK_IP))
            .isInstanceOf(com.paypilot.exception.PaymentDeclinedException.class);
        assertThat(paymentRepoA.findByPaypalOrderId("ORD-MYSQL-PEND-4").orElseThrow().getActiveInvoiceId()).isNull();
        assertThat(invoiceRepoA.findById(inv4.id()).orElseThrow().getStatus()).isEqualTo(InvoiceStatus.APPROVED);
    }

    @Test
    void enforcesRealMySqlUniqueConstraintsForActiveSlotsIntentsAndIdempotencyKeys() {
        User owner = seedUser("mysql.constraints@paypilot.test");
        InvoiceResponse inv = createAndApproveInvoice(owner.getId(), new BigDecimal("333.00"), "USD");
        Invoice invoiceEntity = invoiceRepoA.findById(inv.id()).orElseThrow();

        Payment firstActive = paymentRepoA.saveAndFlush(Payment.builder()
            .invoice(invoiceEntity)
            .paypalOrderId("ORD-MYSQL-UQ-1")
            .idempotencyKey("uq-ord-key-1")
            .captureIdempotencyKey("uq-cap-key-1")
            .amount(new BigDecimal("333.00"))
            .currency("USD")
            .status(PaymentStatus.CAPTURED)
            .paypalCaptureId("CAP-MYSQL-UQ-1")
            .build());
        assertThat(firstActive.getActiveInvoiceId()).isEqualTo(inv.id());

        // Second payment row against same invoice blocked by MySQL uq_payments_active_invoice
        assertThatThrownBy(() -> paymentRepoA.saveAndFlush(Payment.builder()
            .invoice(invoiceEntity)
            .paypalOrderId("ORD-MYSQL-UQ-2")
            .idempotencyKey("uq-ord-key-2")
            .amount(new BigDecimal("333.00"))
            .currency("USD")
            .status(PaymentStatus.CREATED)
            .build()))
            .isInstanceOf(DataIntegrityViolationException.class);

        // Even with FAILED status, duplicate idempotency_key blocked by MySQL uq_payments_idempotency_key
        assertThatThrownBy(() -> paymentRepoA.saveAndFlush(Payment.builder()
            .invoice(invoiceEntity)
            .paypalOrderId("ORD-MYSQL-UQ-3")
            .idempotencyKey("uq-ord-key-1")
            .amount(new BigDecimal("333.00"))
            .currency("USD")
            .status(PaymentStatus.FAILED)
            .build()))
            .isInstanceOf(DataIntegrityViolationException.class);

        // Duplicate capture_idempotency_key across invoices blocked by MySQL uq_payments_capture_idempotency_key
        InvoiceResponse inv2 = createAndApproveInvoice(owner.getId(), new BigDecimal("444.00"), "USD");
        Invoice invoiceEntity2 = invoiceRepoA.findById(inv2.id()).orElseThrow();
        assertThatThrownBy(() -> paymentRepoA.saveAndFlush(Payment.builder()
            .invoice(invoiceEntity2)
            .paypalOrderId("ORD-MYSQL-UQ-4")
            .idempotencyKey("uq-ord-key-4")
            .captureIdempotencyKey("uq-cap-key-1")
            .amount(new BigDecimal("444.00"))
            .currency("USD")
            .status(PaymentStatus.APPROVED)
            .build()))
            .isInstanceOf(DataIntegrityViolationException.class);

        // Concurrent PENDING intents for same invoice blocked by MySQL uq_poi_active_invoice
        intentRepoA.saveAndFlush(PaymentOrderIntent.builder()
            .invoice(invoiceEntity)
            .invoiceSnapshotHash(inv.snapshotHash())
            .idempotencyKey("uq-poi-key-1")
            .attemptNumber(0)
            .amount(new BigDecimal("333.00"))
            .currency("USD")
            .status(PaymentOrderIntentStatus.PENDING)
            .build());

        assertThatThrownBy(() -> intentRepoA.saveAndFlush(PaymentOrderIntent.builder()
            .invoice(invoiceEntity)
            .invoiceSnapshotHash(inv.snapshotHash())
            .idempotencyKey("uq-poi-key-2")
            .attemptNumber(1)
            .amount(new BigDecimal("333.00"))
            .currency("USD")
            .status(PaymentOrderIntentStatus.PENDING)
            .build()))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void expireLeaseViaHibernate(Long invoiceId) {
        PlatformTransactionManager txManager = contextB.getBean(PlatformTransactionManager.class);
        EntityManager entityManager = contextB.getBean(EntityManager.class);
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            int updated = entityManager.createQuery(
                    "UPDATE Invoice i SET i.paymentLockOwner = :owner, i.paymentLockUntil = :expiredAt "
                        + "WHERE i.id = :id"
                )
                .setParameter("owner", "crashed-node-1")
                .setParameter("expiredAt", LocalDateTime.now().minusMinutes(5))
                .setParameter("id", invoiceId)
                .executeUpdate();
            assertThat(updated).isEqualTo(1);
        });
    }

    private User seedUser(String email) {
        return userRepoA.saveAndFlush(User.builder()
            .email(email)
            .name("MySQL Test User")
            .passwordHash("hashed-password")
            .role(UserRole.USER)
            .build());
    }

    private InvoiceResponse createAndApproveInvoice(Long userId, BigDecimal amount, String currency) {
        InvoiceResponse draft = invoiceServiceA.createInvoice(
            userId,
            new CreateInvoiceRequest(
                "MySQL Client",
                "client@mysql.test",
                "Cross-instance payment verification test",
                amount,
                currency,
                LocalDate.now().plusDays(14)
            ),
            LOOPBACK_IP
        );
        return invoiceServiceA.approveInvoice(
            userId,
            draft.id(),
            new ApproveInvoiceRequest(draft.snapshotHash(), true),
            LOOPBACK_IP
        );
    }

    private void mockOrderCreateSuccess(
            PayPalSandboxClient mockClient,
            String orderId,
            BigDecimal amount,
            String currency) {
        when(mockClient.createOrder(any(PayPalCreateOrderRequest.class))).thenAnswer(inv -> {
            PayPalCreateOrderRequest req = inv.getArgument(0);
            return new PayPalOrderResult(
                orderId,
                "PAYER_ACTION_REQUIRED",
                "https://www.sandbox.paypal.com/checkoutnow?token=" + orderId,
                "payer-action",
                req.idempotencyKey(),
                amount,
                currency,
                Map.of("orderId", orderId, "status", "PAYER_ACTION_REQUIRED")
            );
        });
    }

    private static ConfigurableApplicationContext launchInstanceContext() {
        Map<String, Object> overrides = new HashMap<>();
        overrides.put("spring.jmx.enabled", "false");
        overrides.put("spring.main.banner-mode", "off");
        overrides.put("spring.main.allow-bean-definition-overriding", "true");
        overrides.put("spring.datasource.url", testDbUrl);
        overrides.put("spring.datasource.username", dbUser);
        overrides.put("spring.datasource.password", dbPassword);
        overrides.put("spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        overrides.put("spring.jpa.hibernate.ddl-auto", "validate");
        overrides.put("spring.jpa.open-in-view", "false");
        overrides.put("spring.jpa.properties.hibernate.dialect", "org.hibernate.dialect.MySQLDialect");
        overrides.put("spring.flyway.enabled", "false");
        overrides.put("paypal.client-id", "mysql-test-client-id");
        overrides.put("paypal.client-secret", "mysql-test-client-secret");
        overrides.put("paypal.mode", "sandbox");
        overrides.put("paypal.base-url", "https://api-m.sandbox.paypal.com");
        overrides.put("jwt.secret", "mysql-test-jwt-secret-minimum-32-characters-long");
        overrides.put("ai.service.secret", "mysql-test-ai-secret");

        return new SpringApplicationBuilder(PayPilotApplication.class, MockPayPalTestConfig.class)
            .web(WebApplicationType.NONE)
            .initializers(ctx -> ctx.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("mysqlTestOverrides", overrides)))
            .run();
    }

    private static void verifyConnectedToIsolatedRealMySql(ConfigurableApplicationContext ctx) throws Exception {
        DataSource ds = ctx.getBean(DataSource.class);
        try (Connection conn = ds.getConnection()) {
            assertThat(conn.getMetaData().getDatabaseProductName()).isEqualToIgnoringCase("MySQL");
            assertThat(conn.getCatalog()).isEqualTo(TEST_SCHEMA);
        }
    }

    private static Map<String, String> loadDatabaseCredentials() throws Exception {
        Map<String, String> values = new HashMap<>();
        for (Path candidate : List.of(Path.of(".env"), Path.of("backend", ".env"))) {
            if (Files.isRegularFile(candidate)) {
                for (String rawLine : Files.readAllLines(candidate)) {
                    String trimmed = rawLine.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    int eq = trimmed.indexOf('=');
                    if (eq > 0) {
                        values.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
                    }
                }
                break;
            }
        }
        for (String key : List.of("SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD")) {
            String sys = System.getenv(key);
            if (sys != null && !sys.isBlank()) {
                values.put(key, sys.trim());
            }
        }
        return values;
    }

    private static String requireValue(Map<String, String> map, String key) {
        String value = map.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required database configuration property: " + key);
        }
        return value;
    }

    @TestConfiguration
    static class MockPayPalTestConfig {
        @Bean("payPalSandboxClient")
        @Primary
        PayPalSandboxClient payPalSandboxClient() {
            return Mockito.mock(PayPalSandboxClient.class);
        }
    }
}
