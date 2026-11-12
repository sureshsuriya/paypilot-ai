package com.paypilot.dto.paymentlink;

import com.paypilot.model.PaymentLink;

import java.time.LocalDateTime;

/**
 * Non-secret payment link metadata exposed to the owning seller.
 * Never exposes SHA-256 token_hash or raw secret tokens.
 */
public record PaymentLinkSummaryResponse(
    Long id,
    Long invoiceId,
    String tokenPrefix,
    boolean active,
    boolean expired,
    boolean revoked,
    LocalDateTime expiresAt,
    LocalDateTime revokedAt,
    String revocationReason,
    int accessCount,
    LocalDateTime lastAccessedAt,
    LocalDateTime createdAt
) {

    public static PaymentLinkSummaryResponse from(PaymentLink link, LocalDateTime now) {
        boolean revoked = link.isRevoked();
        boolean expired = link.isExpired(now);
        boolean active = !revoked && !expired;
        return new PaymentLinkSummaryResponse(
            link.getId(),
            link.getInvoice().getId(),
            link.getTokenPrefix(),
            active,
            expired,
            revoked,
            link.getExpiresAt(),
            link.getRevokedAt(),
            link.getRevocationReason(),
            link.getAccessCount(),
            link.getLastAccessedAt(),
            link.getCreatedAt()
        );
    }
}

