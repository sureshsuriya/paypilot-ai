package com.paypilot.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI transparency record for invoice extraction operations.
 * Tracks model used, token usage, confidence score, extraction method,
 * and whether a human explicitly verified the extracted fields.
 */
@Entity
@Table(
    name = "ai_extraction_logs",
    indexes = {
        @Index(name = "idx_ai_logs_invoice_id", columnList = "invoice_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiExtractionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Nullable until the user validates and persists the extracted invoice draft.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
        name = "invoice_id",
        foreignKey = @ForeignKey(name = "fk_ai_logs_invoice")
    )
    @ToString.Exclude
    private Invoice invoice;

    @NotBlank(message = "Model name is required")
    @Size(max = 100)
    @Column(name = "model_used", nullable = false, length = 100)
    private String modelUsed;

    @Min(0)
    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Min(0)
    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @DecimalMin(value = "0.0000")
    @DecimalMax(value = "1.0000")
    @Digits(integer = 1, fraction = 4)
    @Column(name = "confidence_score", precision = 5, scale = 4)
    private BigDecimal confidenceScore;

    @NotNull
    @Builder.Default
    @Column(name = "validated_by_user", nullable = false)
    private Boolean validatedByUser = Boolean.FALSE;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "extraction_method", nullable = false, length = 50)
    private ExtractionMethod extractionMethod = ExtractionMethod.TEXT;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.validatedByUser == null) {
            this.validatedByUser = Boolean.FALSE;
        }
        if (this.extractionMethod == null) {
            this.extractionMethod = ExtractionMethod.TEXT;
        }
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}

