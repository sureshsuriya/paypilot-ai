package com.paypilot.controller;

import com.paypilot.dto.ai.ExtractInvoicePromptRequest;
import com.paypilot.dto.ai.ExtractedInvoiceDraftResponse;
import com.paypilot.security.AuthenticatedUser;
import com.paypilot.service.AiInvoiceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticated backend proxy for AI operations.
 * Browser clients call Spring Boot here and never communicate directly with Node.js ai-service.
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiInvoiceService aiInvoiceService;

    @PostMapping("/extract-invoice")
    public ResponseEntity<ExtractedInvoiceDraftResponse> extractInvoice(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @Valid @RequestBody ExtractInvoicePromptRequest request,
            HttpServletRequest httpRequest) {
        ExtractedInvoiceDraftResponse draft = aiInvoiceService.extractInvoiceDraft(
            currentUser.getId(), request.prompt(), httpRequest.getRemoteAddr());
        return ResponseEntity.ok(draft);
    }
}

