package com.paypilot.controller;

import com.paypilot.dto.invoice.ApproveInvoiceRequest;
import com.paypilot.dto.invoice.CreateInvoiceRequest;
import com.paypilot.dto.invoice.InvoiceResponse;
import com.paypilot.dto.invoice.UpdateInvoiceRequest;
import com.paypilot.model.InvoiceStatus;
import com.paypilot.security.AuthenticatedUser;
import com.paypilot.service.InvoiceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * REST API controller for authenticated, user-scoped Invoice CRUD operations.
 * Ownership is always resolved from the authenticated JWT principal.
 */
@RestController
@RequestMapping("/api/invoices")
@RequiredArgsConstructor
public class InvoiceController {

    private final InvoiceService invoiceService;

    @PostMapping
    public ResponseEntity<InvoiceResponse> createInvoice(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @Valid @RequestBody CreateInvoiceRequest request,
            HttpServletRequest httpRequest) {
        InvoiceResponse created = invoiceService.createInvoice(
            currentUser.getId(), request, httpRequest.getRemoteAddr());

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(created.id())
            .toUri();

        return ResponseEntity.created(location).body(created);
    }

    @GetMapping
    public ResponseEntity<List<InvoiceResponse>> listInvoices(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @RequestParam(name = "status", required = false) InvoiceStatus status) {
        return ResponseEntity.ok(invoiceService.listInvoices(currentUser.getId(), status));
    }

    @GetMapping("/{id}")
    public ResponseEntity<InvoiceResponse> getInvoice(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable("id") Long id) {
        return ResponseEntity.ok(invoiceService.getInvoice(currentUser.getId(), id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<InvoiceResponse> updateInvoice(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable("id") Long id,
            @Valid @RequestBody UpdateInvoiceRequest request,
            HttpServletRequest httpRequest) {
        InvoiceResponse updated = invoiceService.updateInvoice(
            currentUser.getId(), id, request, httpRequest.getRemoteAddr());
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<InvoiceResponse> approveInvoice(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable("id") Long id,
            @Valid @RequestBody ApproveInvoiceRequest request,
            HttpServletRequest httpRequest) {
        InvoiceResponse approved = invoiceService.approveInvoice(
            currentUser.getId(), id, request, httpRequest.getRemoteAddr());
        return ResponseEntity.ok(approved);
    }

    @PostMapping("/{id}/revoke-approval")
    public ResponseEntity<InvoiceResponse> revokeApproval(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable("id") Long id,
            HttpServletRequest httpRequest) {
        InvoiceResponse revoked = invoiceService.revokeApproval(
            currentUser.getId(), id, httpRequest.getRemoteAddr());
        return ResponseEntity.ok(revoked);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDraftInvoice(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable("id") Long id,
            HttpServletRequest httpRequest) {
        invoiceService.deleteDraftInvoice(currentUser.getId(), id, httpRequest.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }
}

