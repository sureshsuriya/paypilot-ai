package com.paypilot.controller;

import com.paypilot.dto.paymentlink.CreatePaymentLinkRequest;
import com.paypilot.dto.paymentlink.GeneratedPaymentLinkResponse;
import com.paypilot.dto.paymentlink.PaymentLinkSummaryResponse;
import com.paypilot.dto.paymentlink.RevokePaymentLinkRequest;
import com.paypilot.security.AuthenticatedUser;
import com.paypilot.service.PaymentLinkService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Seller-authenticated REST endpoints to generate, inspect, rotate, and revoke
 * secure customer payment links for owned APPROVED invoices.
 */
@RestController
@RequestMapping("/api/invoices/{invoiceId}/payment-links")
@RequiredArgsConstructor
public class SellerPaymentLinkController {

    private final PaymentLinkService paymentLinkService;

    @PostMapping
    public ResponseEntity<GeneratedPaymentLinkResponse> generateOrRotateLink(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable("invoiceId") Long invoiceId,
            @Valid @RequestBody(required = false) CreatePaymentLinkRequest request,
            HttpServletRequest httpRequest) {
        Integer expiresInDays = request != null ? request.expiresInDays() : null;
        GeneratedPaymentLinkResponse generated = paymentLinkService.generateOrRotateLink(
            currentUser.getId(),
            invoiceId,
            expiresInDays,
            httpRequest.getRemoteAddr()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(generated);
    }

    @GetMapping
    public ResponseEntity<List<PaymentLinkSummaryResponse>> listLinksForInvoice(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable("invoiceId") Long invoiceId) {
        return ResponseEntity.ok(paymentLinkService.listLinksForInvoice(currentUser.getId(), invoiceId));
    }

    @PostMapping("/revoke")
    public ResponseEntity<PaymentLinkSummaryResponse> revokeActiveLink(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable("invoiceId") Long invoiceId,
            @Valid @RequestBody(required = false) RevokePaymentLinkRequest request,
            HttpServletRequest httpRequest) {
        String reason = request != null ? request.reason() : null;
        return ResponseEntity.ok(paymentLinkService.revokeActiveLink(
            currentUser.getId(),
            invoiceId,
            reason,
            httpRequest.getRemoteAddr()
        ));
    }
}
