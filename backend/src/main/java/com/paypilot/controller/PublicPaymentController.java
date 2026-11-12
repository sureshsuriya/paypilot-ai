package com.paypilot.controller;

import com.paypilot.dto.publicpay.PublicCancelOrderRequest;
import com.paypilot.dto.publicpay.PublicCaptureOrderRequest;
import com.paypilot.dto.publicpay.PublicCreateOrderRequest;
import com.paypilot.dto.publicpay.PublicPaymentInvoiceResponse;
import com.paypilot.dto.publicpay.PublicPaymentOrderResponse;
import com.paypilot.service.PaymentLinkService;
import com.paypilot.service.PaymentService;
import com.paypilot.service.PaymentService.PublicCaptureOutcome;
import com.paypilot.service.PaymentService.PublicPaymentOrderOutcome;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unauthenticated Customer Portal REST controller authorized strictly by
 * cryptographically random payment-link tokens (SHA-256 verified server-side).
 * Exposes zero seller data, AI logs, or arbitrary invoice access.
 */
@RestController
@RequestMapping("/api/public/pay")
@RequiredArgsConstructor
public class PublicPaymentController {

    private final PaymentLinkService paymentLinkService;
    private final PaymentService paymentService;

    @GetMapping("/{token}")
    public ResponseEntity<PublicPaymentInvoiceResponse> getPublicInvoice(
            @PathVariable("token") String token,
            @RequestParam(name = "trackView", defaultValue = "true") boolean trackView) {
        return ResponseEntity.ok(paymentLinkService.buildPublicPaymentView(token, trackView));
    }

    @PostMapping("/{token}/orders")
    public ResponseEntity<PublicPaymentOrderResponse> createOrder(
            @PathVariable("token") String token,
            @Valid @RequestBody(required = false) PublicCreateOrderRequest request,
            HttpServletRequest httpRequest) {
        PublicPaymentOrderOutcome outcome = paymentService.createOrderForPaymentLink(
            token,
            request != null ? request.returnUrl() : null,
            request != null ? request.cancelUrl() : null,
            httpRequest.getRemoteAddr()
        );
        HttpStatus status = outcome.reused() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(outcome.response());
    }

    @PostMapping("/{token}/capture")
    public ResponseEntity<PublicPaymentInvoiceResponse> captureOrder(
            @PathVariable("token") String token,
            @Valid @RequestBody PublicCaptureOrderRequest request,
            HttpServletRequest httpRequest) {
        PublicCaptureOutcome outcome = paymentService.captureOrderForPaymentLink(
            token,
            request.orderId(),
            httpRequest.getRemoteAddr()
        );
        HttpStatus status = outcome.pending() ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(outcome.response());
    }

    @PostMapping("/{token}/cancel")
    public ResponseEntity<PublicPaymentInvoiceResponse> cancelOrder(
            @PathVariable("token") String token,
            @Valid @RequestBody(required = false) PublicCancelOrderRequest request,
            HttpServletRequest httpRequest) {
        PublicPaymentInvoiceResponse response = paymentService.cancelOrderForPaymentLink(
            token,
            request != null ? request.orderId() : null,
            request != null ? request.reason() : null,
            httpRequest.getRemoteAddr()
        );
        return ResponseEntity.ok(response);
    }
}

