package com.paypilot.service;

import com.paypilot.audit.AuditLogService;
import com.paypilot.dto.ai.AiServiceExtractResponse;
import com.paypilot.dto.ai.ExtractedDraftFields;
import com.paypilot.dto.ai.ExtractedInvoiceDraftResponse;
import com.paypilot.model.AiExtractionLog;
import com.paypilot.model.AuditActor;
import com.paypilot.model.ExtractionMethod;
import com.paypilot.repository.AiExtractionLogRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Authoritative backend orchestrator for AI invoice extraction.
 *
 * COMPLIANCE & SAFETY:
 *   - Re-validates every extracted field in Java (BigDecimal precision, email, currency, due date).
 *   - Never auto-saves an Invoice; returns a draft preview requiring human confirmation.
 *   - Persists AI transparency metadata in ai_extraction_logs and audit_logs.
 */
@Service
public class AiInvoiceService {

    private static final Pattern CURRENCY_PATTERN = Pattern.compile("^[A-Z]{3}$");
    private static final BigDecimal MIN_AMOUNT = new BigDecimal("0.01");
    private static final String DEFAULT_GEMINI_MODEL = "gemini-3.5-flash-lite";
    private static final String AI_REVIEW_LABEL =
        "AI-extracted draft — please verify all details before saving";

    private final AiServiceClient aiServiceClient;
    private final AiExtractionLogRepository extractionLogRepository;
    private final AuditLogService auditLogService;
    private final Validator validator;
    private final String defaultModel;

    @Autowired
    public AiInvoiceService(
            AiServiceClient aiServiceClient,
            AiExtractionLogRepository extractionLogRepository,
            AuditLogService auditLogService,
            Validator validator,
            @Value("${ai.default-model:" + DEFAULT_GEMINI_MODEL + "}") String defaultModel) {
        this.aiServiceClient = aiServiceClient;
        this.extractionLogRepository = extractionLogRepository;
        this.auditLogService = auditLogService;
        this.validator = validator;
        String normalized = normalizeText(defaultModel);
        this.defaultModel = normalized != null ? normalized : DEFAULT_GEMINI_MODEL;
    }

    @Transactional
    public ExtractedInvoiceDraftResponse extractInvoiceDraft(
            Long authenticatedUserId,
            String prompt,
            String ipAddress) {

        LocalDate today = LocalDate.now();
        AiServiceExtractResponse raw = aiServiceClient.extractInvoice(prompt.trim(), today);

        AiServiceExtractResponse.RawDraftPayload rawDraft = raw.draft();
        List<String> missingFields = new ArrayList<>();
        Map<String, String> validationErrors = new LinkedHashMap<>();

        String clientName = normalizeText(rawDraft.clientName());
        if (clientName == null) {
            missingFields.add("clientName");
        } else if (clientName.length() > 255) {
            validationErrors.put("clientName", "Client name must not exceed 255 characters");
        }

        String rawEmail = normalizeText(rawDraft.clientEmail());
        String clientEmail = rawEmail != null ? rawEmail.toLowerCase(Locale.ROOT) : null;
        if (clientEmail == null) {
            missingFields.add("clientEmail");
        } else if (clientEmail.length() > 255 || !isValidEmail(clientEmail)) {
            validationErrors.put("clientEmail", "Client email must be valid");
        }

        String description = normalizeText(rawDraft.description());
        if (description == null) {
            missingFields.add("description");
        } else if (description.length() > 5000) {
            validationErrors.put("description", "Description must not exceed 5000 characters");
        }

        BigDecimal amount = rawDraft.amount();
        if (amount == null) {
            missingFields.add("amount");
        } else if (amount.compareTo(MIN_AMOUNT) < 0) {
            validationErrors.put("amount", "Amount must be at least 0.01");
        } else if (amount.stripTrailingZeros().scale() > 2) {
            validationErrors.put("amount", "Amount must have at most 2 decimal places");
        } else if (amount.precision() - amount.scale() > 13) {
            validationErrors.put("amount", "Amount exceeds maximum supported digits");
        } else {
            amount = amount.setScale(2, RoundingMode.UNNECESSARY);
        }

        String rawCurrency = normalizeText(rawDraft.currency());
        String currency = rawCurrency != null ? rawCurrency.toUpperCase(Locale.ROOT) : null;
        if (currency == null) {
            missingFields.add("currency");
        } else if (!CURRENCY_PATTERN.matcher(currency).matches()) {
            validationErrors.put("currency", "Currency must be a 3-letter ISO code (e.g. USD)");
        }

        LocalDate dueDate = null;
        String rawDueDate = normalizeText(rawDraft.dueDate());
        if (rawDueDate == null) {
            missingFields.add("dueDate");
        } else {
            try {
                LocalDate parsedDate = LocalDate.parse(rawDueDate);
                if (parsedDate.isBefore(today)) {
                    validationErrors.put("dueDate", "Due date must be today or in the future");
                } else {
                    dueDate = parsedDate;
                }
            } catch (DateTimeParseException ex) {
                validationErrors.put("dueDate", "Due date must be a valid YYYY-MM-DD date");
            }
        }

        BigDecimal confidence = clampConfidence(raw.confidence());
        String modelUsed = normalizeText(raw.modelUsed()) != null ? raw.modelUsed().trim() : defaultModel;
        if (modelUsed.length() > 100) {
            modelUsed = modelUsed.substring(0, 100);
        }

        Integer promptTokens = raw.tokenUsage() != null ? raw.tokenUsage().promptTokens() : null;
        Integer completionTokens = raw.tokenUsage() != null ? raw.tokenUsage().completionTokens() : null;

        AiExtractionLog savedLog = extractionLogRepository.save(AiExtractionLog.builder()
            .invoice(null)
            .modelUsed(modelUsed)
            .promptTokens(promptTokens)
            .completionTokens(completionTokens)
            .confidenceScore(confidence)
            .validatedByUser(Boolean.FALSE)
            .extractionMethod(ExtractionMethod.TEXT)
            .build());

        boolean readyToSave = missingFields.isEmpty() && validationErrors.isEmpty();

        auditLogService.logEvent(
            authenticatedUserId,
            "AI_EXTRACTION",
            savedLog.getId(),
            "AI_INVOICE_EXTRACTED",
            AuditActor.AI_AGENT,
            Map.of(
                "modelUsed", modelUsed,
                "sourceMode", raw.sourceMode() != null ? raw.sourceMode() : "live",
                "readyToSave", readyToSave,
                "missingFields", List.copyOf(missingFields)
            ),
            ipAddress
        );

        ExtractedDraftFields draftFields = new ExtractedDraftFields(
            clientName,
            clientEmail,
            description,
            amount,
            currency,
            dueDate
        );

        return new ExtractedInvoiceDraftResponse(
            savedLog.getId(),
            true,
            true,
            AI_REVIEW_LABEL,
            raw.sourceMode() != null ? raw.sourceMode() : "live",
            modelUsed,
            confidence,
            normalizeText(raw.assumptions()),
            draftFields,
            List.copyOf(missingFields),
            Map.copyOf(validationErrors),
            readyToSave
        );
    }

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private BigDecimal clampConfidence(BigDecimal rawConfidence) {
        if (rawConfidence == null) {
            return null;
        }
        BigDecimal clamped = rawConfidence.max(BigDecimal.ZERO).min(BigDecimal.ONE);
        return clamped.setScale(4, RoundingMode.HALF_UP);
    }

    private boolean isValidEmail(String email) {
        Set<ConstraintViolation<EmailProbe>> violations = validator.validate(new EmailProbe(email));
        return violations.isEmpty();
    }

    private record EmailProbe(@Email String email) {
    }
}

