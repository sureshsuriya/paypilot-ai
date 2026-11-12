package com.paypilot.controller;

import com.paypilot.dto.email.InvoiceDeliveryLogResponse;
import com.paypilot.dto.email.InvoiceEmailPreviewResponse;
import com.paypilot.dto.email.InvoiceEmailRequest;
import com.paypilot.dto.email.InvoiceEmailSendResponse;
import com.paypilot.security.AuthenticatedUser;
import com.paypilot.service.InvoiceEmailService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
 * Seller-authenticated controller to preview and send HTML invoice payment-link emails
 * and view sanitized delivery history.
 */
@RestController
@RequestMapping("/api/invoices/{invoiceId}")
@RequiredArgsConstructor
public class InvoiceDeliveryController {

    private final InvoiceEmailService invoiceEmailService;

    @PostMapping("/email/preview")
    public ResponseEntity<InvoiceEmailPreviewResponse> previewEmail(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable("invoiceId") Long invoiceId,
            @Valid @RequestBody InvoiceEmailRequest request) {
        return ResponseEntity.ok(
            invoiceEmailService.previewInvoiceEmail(currentUser.getId(), invoiceId, request)
        );
    }

    @PostMapping("/email/send")
    public ResponseEntity<InvoiceEmailSendResponse> sendEmail(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable("invoiceId") Long invoiceId,
            @Valid @RequestBody InvoiceEmailRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
            invoiceEmailService.sendInvoiceEmail(
                currentUser.getId(), invoiceId, request, httpRequest.getRemoteAddr()
            )
        );
    }

    @GetMapping("/deliveries")
    public ResponseEntity<List<InvoiceDeliveryLogResponse>> listDeliveries(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable("invoiceId") Long invoiceId) {
        return ResponseEntity.ok(
            invoiceEmailService.listDeliveriesForInvoice(currentUser.getId(), invoiceId)
        );
    }
}

