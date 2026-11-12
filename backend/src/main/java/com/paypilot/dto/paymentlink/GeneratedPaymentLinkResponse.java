package com.paypilot.dto.paymentlink;

/**
 * Returned once when a seller generates or rotates a secure payment link.
 * The rawToken and paymentUrl are never persisted on the server (only SHA-256 token_hash is stored).
 */
public record GeneratedPaymentLinkResponse(
    PaymentLinkSummaryResponse link,
    String paymentUrl,
    String rawToken
) {
}

