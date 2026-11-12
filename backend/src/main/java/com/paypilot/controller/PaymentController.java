package com.paypilot.controller;

import com.paypilot.dto.payment.CancelPaymentRequest;
import com.paypilot.dto.payment.CreatePaymentOrderRequest;
import com.paypilot.dto.payment.PaymentResponse;
import com.paypilot.model.PaymentStatus;
import com.paypilot.security.AuthenticatedUser;
import com.paypilot.service.PaymentService;
import com.paypilot.service.PaymentService.PaymentOrderOutcome;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST API controller for authenticated PayPal Sandbox payment orchestration.
 * All endpoints enforce JWT ownership and explicit invoice approval verification.
 */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @GetMapping
    public ResponseEntity<List<PaymentResponse>> listPayments(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @RequestParam(name = "status", required = false) PaymentStatus status) {
        return ResponseEntity.ok(paymentService.listPaymentsForSeller(currentUser.getId(), status));
    }

    @PostMapping("/orders")
    public ResponseEntity<PaymentResponse> createOrder(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @Valid @RequestBody CreatePaymentOrderRequest request,
            HttpServletRequest httpRequest) {
        PaymentOrderOutcome outcome = paymentService.createOrder(
            currentUser.getId(), request, httpRequest.getRemoteAddr()
        );
        HttpStatus status = outcome.reused() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(outcome.response());
    }

    @PostMapping("/orders/{orderId}/capture")
    public ResponseEntity<PaymentResponse> captureOrder(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable("orderId") String orderId,
            HttpServletRequest httpRequest) {
        PaymentResponse result = paymentService.captureOrder(
            currentUser.getId(), orderId, httpRequest.getRemoteAddr()
        );
        HttpStatus status = result.pending() ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result);
    }

    @PostMapping("/orders/{orderId}/cancel")
    public ResponseEntity<PaymentResponse> cancelOrder(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable("orderId") String orderId,
            @Valid @RequestBody(required = false) CancelPaymentRequest request,
            HttpServletRequest httpRequest) {
        PaymentResponse result = paymentService.cancelOrder(
            currentUser.getId(), orderId, request, httpRequest.getRemoteAddr()
        );
        return ResponseEntity.ok(result);
    }

    @GetMapping("/invoice/{invoiceId}")
    public ResponseEntity<List<PaymentResponse>> getPaymentHistory(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable("invoiceId") Long invoiceId) {
        return ResponseEntity.ok(paymentService.getPaymentsForInvoice(currentUser.getId(), invoiceId));
    }
}

