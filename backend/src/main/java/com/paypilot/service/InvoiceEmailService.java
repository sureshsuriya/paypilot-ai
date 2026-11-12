package com.paypilot.service;

import com.paypilot.audit.AuditLogService;
import com.paypilot.dto.email.InvoiceDeliveryLogResponse;
import com.paypilot.dto.email.InvoiceEmailPreviewResponse;
import com.paypilot.dto.email.InvoiceEmailRequest;
import com.paypilot.dto.email.InvoiceEmailSendResponse;
import com.paypilot.exception.EmailDeliveryException;
import com.paypilot.exception.ForbiddenOperationException;
import com.paypilot.exception.InvalidInvoiceStateException;
import com.paypilot.exception.ResourceNotFoundException;
import com.paypilot.model.AuditActor;
import com.paypilot.model.Invoice;
import com.paypilot.model.InvoiceDelivery;
import com.paypilot.model.InvoiceStatus;
import com.paypilot.model.PaymentLink;
import com.paypilot.model.PaymentStatus;
import com.paypilot.repository.InvoiceDeliveryRepository;
import com.paypilot.repository.InvoiceRepository;
import com.paypilot.repository.PaymentLinkRepository;
import com.paypilot.repository.PaymentRepository;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Generates HTML/text previews and dispatches approved invoice payment-link emails via Spring JavaMailSender.
 *
 * SECURITY GUARANTEES:
 *   - Verifies invoice ownership, APPROVED status, snapshot integrity, and active link SHA-256 hash match.
 *   - Escapes every dynamic field with HtmlUtils.htmlEscape to prevent HTML injection.
 *   - Executes SMTP I/O strictly outside database transactions and never logs raw link tokens or credentials.
 */
@Slf4j
@Service
public class InvoiceEmailService {

    private static final String CHANNEL_EMAIL = "EMAIL";
    private static final String STATUS_SENT = "SENT";
    private static final String STATUS_FAILED = "FAILED";
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final DateTimeFormatter READABLE_DATE_TIME = DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm");

    private final InvoiceRepository invoiceRepository;
    private final PaymentLinkRepository paymentLinkRepository;
    private final PaymentRepository paymentRepository;
    private final InvoiceDeliveryRepository invoiceDeliveryRepository;
    private final PaymentLinkService paymentLinkService;
    private final InvoiceSnapshotService invoiceSnapshotService;
    private final AuditLogService auditLogService;
    private final TransactionTemplate transactionTemplate;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final Clock clock;
    private final boolean mailEnabled;
    private final String mailHost;
    private final String mailFrom;
    private final long resendCooldownSeconds;
    private final String defaultFrontendOrigin;

    public InvoiceEmailService(
            InvoiceRepository invoiceRepository,
            PaymentLinkRepository paymentLinkRepository,
            PaymentRepository paymentRepository,
            InvoiceDeliveryRepository invoiceDeliveryRepository,
            PaymentLinkService paymentLinkService,
            InvoiceSnapshotService invoiceSnapshotService,
            AuditLogService auditLogService,
            PlatformTransactionManager transactionManager,
            ObjectProvider<JavaMailSender> mailSenderProvider,
            @Qualifier("paymentClock") Clock clock,
            @Value("${paypilot.mail.enabled:false}") boolean mailEnabled,
            @Value("${spring.mail.host:}") String mailHost,
            @Value("${paypilot.mail.from:no-reply@paypilot.local}") String mailFrom,
            @Value("${paypilot.mail.resend-cooldown-seconds:60}") long resendCooldownSeconds,
            @Value("${cors.allowed-origins:http://localhost:5173}") String allowedOriginsRaw) {
        this.invoiceRepository = invoiceRepository;
        this.paymentLinkRepository = paymentLinkRepository;
        this.paymentRepository = paymentRepository;
        this.invoiceDeliveryRepository = invoiceDeliveryRepository;
        this.paymentLinkService = paymentLinkService;
        this.invoiceSnapshotService = invoiceSnapshotService;
        this.auditLogService = auditLogService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.mailSenderProvider = mailSenderProvider;
        this.clock = clock;
        this.mailEnabled = mailEnabled;
        this.mailHost = mailHost != null ? mailHost.trim() : "";
        this.mailFrom = mailFrom != null ? mailFrom.trim() : "";
        this.resendCooldownSeconds = Math.max(0L, resendCooldownSeconds);
        this.defaultFrontendOrigin = resolveDefaultOrigin(allowedOriginsRaw);
    }

    @Transactional(readOnly = true)
    public InvoiceEmailPreviewResponse previewInvoiceEmail(
            Long authenticatedUserId,
            Long invoiceId,
            InvoiceEmailRequest request) {
        PreparedEmailContext prepared = prepareValidatedEmail(authenticatedUserId, invoiceId, request, false);
        return new InvoiceEmailPreviewResponse(
            prepared.recipientEmail(),
            prepared.recipientName(),
            prepared.subject(),
            prepared.htmlBody(),
            prepared.textBody(),
            prepared.paymentUrl(),
            prepared.linkExpiresAt(),
            isMailConfigured()
        );
    }

    public InvoiceEmailSendResponse sendInvoiceEmail(
            Long authenticatedUserId,
            Long invoiceId,
            InvoiceEmailRequest request,
            String ipAddress) {
        PreparedEmailContext prepared = transactionTemplate.execute(
            status -> prepareValidatedEmail(authenticatedUserId, invoiceId, request, true)
        );
        if (prepared == null) {
            throw new IllegalStateException("Unable to prepare invoice email.");
        }

        if (!isMailConfigured()) {
            String configError =
                "Email delivery is disabled or SMTP is not configured. Set MAIL_ENABLED=true, MAIL_HOST, MAIL_USERNAME, and MAIL_PASSWORD in backend/.env.";
            recordDeliveryOutcome(authenticatedUserId, prepared, STATUS_FAILED, configError, ipAddress);
            throw new EmailDeliveryException(configError);
        }

        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            String missingBeanError = "JavaMailSender bean is unavailable because SMTP host is not configured.";
            recordDeliveryOutcome(authenticatedUserId, prepared, STATUS_FAILED, missingBeanError, ipAddress);
            throw new EmailDeliveryException(missingBeanError);
        }

        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                mimeMessage, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, StandardCharsets.UTF_8.name()
            );
            helper.setFrom(mailFrom);
            helper.setTo(prepared.recipientEmail());
            helper.setSubject(prepared.subject());
            helper.setText(prepared.textBody(), prepared.htmlBody());
            mailSender.send(mimeMessage);
        } catch (MailException | MessagingException ex) {
            String safeReason = sanitizeMailFailure(ex.getMessage());
            log.warn("SMTP delivery failed for invoiceId={}: {}", prepared.invoiceId(), safeReason);
            recordDeliveryOutcome(authenticatedUserId, prepared, STATUS_FAILED, safeReason, ipAddress);
            throw new EmailDeliveryException("Unable to send invoice email via SMTP: " + safeReason, ex);
        }

        InvoiceDelivery saved = recordDeliveryOutcome(authenticatedUserId, prepared, STATUS_SENT, null, ipAddress);
        return new InvoiceEmailSendResponse(
            saved.getId(),
            prepared.invoiceId(),
            prepared.recipientEmail(),
            prepared.subject(),
            prepared.tokenPrefix(),
            saved.getCreatedAt(),
            true
        );
    }

    @Transactional(readOnly = true)
    public List<InvoiceDeliveryLogResponse> listDeliveriesForInvoice(Long authenticatedUserId, Long invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
            .orElseThrow(() -> new ResourceNotFoundException("Invoice not found."));
        if (!invoice.getUser().getId().equals(authenticatedUserId)) {
            throw new ForbiddenOperationException("You do not have permission to view deliveries for this invoice.");
        }
        return invoiceDeliveryRepository.findByInvoiceIdOrderByCreatedAtDesc(invoiceId).stream()
            .map(InvoiceDeliveryLogResponse::from)
            .toList();
    }

    public boolean isMailConfigured() {
        return mailEnabled
            && !mailHost.isBlank()
            && !mailFrom.isBlank()
            && mailSenderProvider.getIfAvailable() != null;
    }

    private PreparedEmailContext prepareValidatedEmail(
            Long authenticatedUserId,
            Long invoiceId,
            InvoiceEmailRequest request,
            boolean enforceCooldown) {
        if (request == null || request.rawToken() == null || request.rawToken().isBlank()) {
            throw new InvalidInvoiceStateException(
                "Generate an active customer payment link before composing an invoice email."
            );
        }

        Invoice invoice = invoiceRepository.findById(invoiceId)
            .orElseThrow(() -> new ResourceNotFoundException("Invoice not found."));

        if (!invoice.getUser().getId().equals(authenticatedUserId)) {
            throw new ForbiddenOperationException("You do not have permission to access this invoice.");
        }

        if (invoice.getStatus() != InvoiceStatus.APPROVED) {
            throw new InvalidInvoiceStateException(
                "Only APPROVED invoices can be emailed for customer payment. Current status: " + invoice.getStatus()
            );
        }

        if (paymentRepository.existsByInvoiceIdAndStatus(invoiceId, PaymentStatus.CAPTURED)
                || paymentRepository.existsByInvoiceIdAndStatus(invoiceId, PaymentStatus.PENDING)) {
            throw new InvalidInvoiceStateException(
                "Invoice already has a pending or captured payment and cannot be emailed for checkout."
            );
        }

        PaymentLink activeLink = paymentLinkRepository.findByActiveInvoiceId(invoiceId)
            .orElseThrow(() -> new InvalidInvoiceStateException(
                "No active payment link exists for this invoice. Generate a payment link first."
            ));

        LocalDateTime now = LocalDateTime.now(clock);
        if (activeLink.isRevoked() || activeLink.isExpired(now)) {
            throw new InvalidInvoiceStateException(
                "The active payment link has expired or been revoked. Regenerate a new link before sending email."
            );
        }

        String currentHash = invoiceSnapshotService.computeHash(invoice);
        if (!invoiceSnapshotService.hashesMatch(currentHash, invoice.getApprovedSnapshotHash())
                || !invoiceSnapshotService.hashesMatch(currentHash, activeLink.getInvoiceSnapshotHash())) {
            throw new InvalidInvoiceStateException(
                "Invoice snapshot does not match the active payment link. Re-approve and regenerate the link."
            );
        }

        String normalizedRawToken = request.rawToken().trim();
        if (!paymentLinkService.verifyRawTokenMatchesLink(normalizedRawToken, activeLink)) {
            throw new InvalidInvoiceStateException(
                "The current browser session does not hold the latest active payment-link token. Regenerate the payment link before sending."
            );
        }

        if (enforceCooldown && resendCooldownSeconds > 0) {
            Optional<InvoiceDelivery> latestSent = invoiceDeliveryRepository
                .findFirstByInvoiceIdAndChannelAndStatusOrderByCreatedAtDesc(
                    invoiceId, CHANNEL_EMAIL, STATUS_SENT
                );
            if (latestSent.isPresent() && latestSent.get().getCreatedAt() != null) {
                LocalDateTime earliestAllowed = latestSent.get().getCreatedAt().plusSeconds(resendCooldownSeconds);
                if (now.isBefore(earliestAllowed)) {
                    throw new InvalidInvoiceStateException(
                        "An invoice email was already sent recently. Wait a moment before resending to prevent duplicate emails."
                    );
                }
            }
        }

        String targetEmail = request.recipientEmail() != null && !request.recipientEmail().isBlank()
            ? request.recipientEmail().trim().toLowerCase(Locale.ROOT)
            : invoice.getClientEmail().trim().toLowerCase(Locale.ROOT);
        if (!EMAIL_PATTERN.matcher(targetEmail).matches()) {
            throw new IllegalArgumentException("Recipient email must be a valid email address.");
        }

        String sellerName = invoice.getUser() != null && invoice.getUser().getName() != null
            && !invoice.getUser().getName().isBlank()
            ? invoice.getUser().getName().trim()
            : "PayPilot Merchant";
        String clientName = invoice.getClientName() != null ? invoice.getClientName().trim() : "Customer";
        String customNote = request.customMessage() != null && !request.customMessage().isBlank()
            ? request.customMessage().trim()
            : null;
        String paymentUrl = defaultFrontendOrigin + "/pay/" + normalizedRawToken;
        String formattedAmount = formatAmountWithCurrency(invoice.getAmount(), invoice.getCurrency());
        String dueDateStr = invoice.getDueDate() != null ? invoice.getDueDate().toString() : "Upon receipt";
        String subject = String.format("Invoice %s from %s — %s", invoice.getInvoiceNumber(), sellerName, formattedAmount);
        if (subject.length() > 250) {
            subject = subject.substring(0, 250);
        }

        String htmlBody = buildResponsiveHtmlEmail(
            sellerName, clientName, invoice, formattedAmount, dueDateStr, activeLink.getExpiresAt(), paymentUrl, customNote
        );
        String textBody = buildPlainTextEmail(
            sellerName, clientName, invoice, formattedAmount, dueDateStr, activeLink.getExpiresAt(), paymentUrl, customNote
        );

        return new PreparedEmailContext(
            invoice.getId(),
            authenticatedUserId,
            activeLink.getId(),
            activeLink.getTokenPrefix(),
            targetEmail,
            clientName,
            subject,
            htmlBody,
            textBody,
            paymentUrl,
            activeLink.getExpiresAt()
        );
    }

    private InvoiceDelivery recordDeliveryOutcome(
            Long sellerUserId,
            PreparedEmailContext prepared,
            String status,
            String failureReason,
            String ipAddress) {
        InvoiceDelivery saved = transactionTemplate.execute(txStatus -> {
            InvoiceDelivery entity = InvoiceDelivery.builder()
                .invoice(invoiceRepository.getReferenceById(prepared.invoiceId()))
                .user(invoiceRepository.getReferenceById(prepared.invoiceId()).getUser())
                .paymentLink(paymentLinkRepository.getReferenceById(prepared.paymentLinkId()))
                .channel(CHANNEL_EMAIL)
                .recipient(prepared.recipientEmail())
                .subject(prepared.subject())
                .status(status)
                .failureReason(failureReason)
                .createdAt(LocalDateTime.now(clock))
                .build();
            return invoiceDeliveryRepository.saveAndFlush(entity);
        });

        Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("invoiceId", prepared.invoiceId());
        auditDetails.put("paymentLinkId", prepared.paymentLinkId());
        auditDetails.put("tokenPrefix", prepared.tokenPrefix());
        auditDetails.put("recipientEmail", prepared.recipientEmail());
        auditDetails.put("status", status);
        if (failureReason != null) {
            auditDetails.put("reason", failureReason);
        }
        auditLogService.logIndependentEvent(
            sellerUserId,
            "INVOICE",
            prepared.invoiceId(),
            STATUS_SENT.equals(status) ? "INVOICE_EMAIL_SENT" : "INVOICE_EMAIL_FAILED",
            AuditActor.USER,
            auditDetails,
            ipAddress
        );
        return saved;
    }

    private String buildResponsiveHtmlEmail(
            String sellerName,
            String clientName,
            Invoice invoice,
            String formattedAmount,
            String dueDateStr,
            LocalDateTime expiresAt,
            String paymentUrl,
            String customNote) {
        String safeSeller = HtmlUtils.htmlEscape(sellerName);
        String safeClient = HtmlUtils.htmlEscape(clientName);
        String safeInvoiceNo = HtmlUtils.htmlEscape(invoice.getInvoiceNumber());
        String safeDescription = HtmlUtils.htmlEscape(invoice.getDescription() != null ? invoice.getDescription() : "");
        String safeAmount = HtmlUtils.htmlEscape(formattedAmount);
        String safeDueDate = HtmlUtils.htmlEscape(dueDateStr);
        String safeExpiresAt = HtmlUtils.htmlEscape(expiresAt.format(READABLE_DATE_TIME) + " UTC");
        String safePaymentUrl = HtmlUtils.htmlEscape(paymentUrl);
        String safeCustomNoteBlock = customNote != null
            ? "<div style=\"margin:16px 0;padding:12px 14px;border-left:3px solid #2563eb;background:#f8fafc;border-radius:6px;color:#334155;font-size:14px;line-height:1.5;\">"
                + HtmlUtils.htmlEscape(customNote).replace("\n", "<br/>")
                + "</div>"
            : "";

        return new StringBuilder(1600)
            .append("<!DOCTYPE html>\n<html lang=\"en\">\n<head><meta charset=\"UTF-8\" />")
            .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0\" /></head>\n")
            .append("<body style=\"margin:0;padding:24px;background-color:#f1f5f9;")
            .append("font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;color:#0f172a;\">\n")
            .append("<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" ")
            .append("style=\"max-width:600px;margin:0 auto;background:#ffffff;border:1px solid #e2e8f0;border-radius:16px;overflow:hidden;\">\n")
            .append("<tr><td style=\"padding:24px 28px;background:#0f172a;color:#ffffff;\">")
            .append("<div style=\"font-size:12px;letter-spacing:0.08em;text-transform:uppercase;color:#93c5fd;font-weight:700;\">")
            .append("PayPilot AI &middot; Secure Payment Request</div>")
            .append("<h1 style=\"margin:8px 0 0;font-size:22px;font-weight:700;color:#ffffff;\">Invoice ")
            .append(safeInvoiceNo).append("</h1>")
            .append("<p style=\"margin:4px 0 0;font-size:13px;color:#cbd5e1;\">From ").append(safeSeller).append("</p>")
            .append("</td></tr>\n<tr><td style=\"padding:24px 28px;\">")
            .append("<p style=\"margin:0 0 12px;font-size:15px;line-height:1.5;\">Hello ").append(safeClient).append(",</p>")
            .append("<p style=\"margin:0 0 16px;font-size:14px;line-height:1.6;color:#475569;\">")
            .append("Your invoice has been reviewed and approved. Please review the details below and pay securely through PayPal Sandbox.</p>")
            .append(safeCustomNoteBlock)
            .append("<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" ")
            .append("style=\"border:1px solid #e2e8f0;border-radius:12px;background:#f8fafc;margin:16px 0;\">")
            .append("<tr><td style=\"padding:14px 16px;border-bottom:1px solid #e2e8f0;font-size:13px;color:#64748b;\">Amount Due</td>")
            .append("<td style=\"padding:14px 16px;border-bottom:1px solid #e2e8f0;font-size:16px;font-weight:700;color:#0f172a;text-align:right;\">")
            .append(safeAmount).append("</td></tr>")
            .append("<tr><td style=\"padding:12px 16px;border-bottom:1px solid #e2e8f0;font-size:13px;color:#64748b;\">Due Date</td>")
            .append("<td style=\"padding:12px 16px;border-bottom:1px solid #e2e8f0;font-size:13px;font-weight:600;color:#0f172a;text-align:right;\">")
            .append(safeDueDate).append("</td></tr>")
            .append("<tr><td style=\"padding:12px 16px;border-bottom:1px solid #e2e8f0;font-size:13px;color:#64748b;\">Link Expires</td>")
            .append("<td style=\"padding:12px 16px;border-bottom:1px solid #e2e8f0;font-size:13px;color:#334155;text-align:right;\">")
            .append(safeExpiresAt).append("</td></tr>")
            .append("<tr><td colspan=\"2\" style=\"padding:12px 16px;font-size:13px;color:#334155;\">")
            .append("<div style=\"font-size:11px;text-transform:uppercase;letter-spacing:0.06em;color:#64748b;margin-bottom:4px;\">Description</div>")
            .append(safeDescription).append("</td></tr></table>")
            .append("<div style=\"text-align:center;margin:24px 0;\">")
            .append("<a href=\"").append(safePaymentUrl)
            .append("\" style=\"display:inline-block;padding:13px 26px;background:#2563eb;color:#ffffff;text-decoration:none;font-size:14px;font-weight:600;border-radius:10px;\">")
            .append("Review &amp; Pay Invoice</a></div>")
            .append("<p style=\"margin:0 0 8px;font-size:12px;color:#64748b;\">If the button does not work, copy and open this secure link:</p>")
            .append("<p style=\"margin:0;font-size:12px;word-break:break-all;color:#2563eb;\">").append(safePaymentUrl).append("</p>")
            .append("</td></tr>\n<tr><td style=\"padding:16px 28px;background:#fffbeb;border-top:1px solid #fde68a;font-size:12px;color:#92400e;\">")
            .append("PayPal Sandbox Mode &mdash; Test environment only. Payment is verified securely by PayPilot backend servers.")
            .append("</td></tr></table></body></html>")
            .toString();
    }

    private String buildPlainTextEmail(
            String sellerName,
            String clientName,
            Invoice invoice,
            String formattedAmount,
            String dueDateStr,
            LocalDateTime expiresAt,
            String paymentUrl,
            String customNote) {
        StringBuilder sb = new StringBuilder();
        sb.append("Hello ").append(clientName).append(",\n\n");
        sb.append("Your invoice ").append(invoice.getInvoiceNumber())
          .append(" from ").append(sellerName).append(" is ready.\n\n");
        if (customNote != null) {
            sb.append("Message: ").append(customNote).append("\n\n");
        }
        sb.append("Amount: ").append(formattedAmount).append("\n");
        sb.append("Description: ").append(invoice.getDescription() != null ? invoice.getDescription() : "").append("\n");
        sb.append("Due date: ").append(dueDateStr).append("\n");
        sb.append("Payment link expires: ").append(expiresAt.format(READABLE_DATE_TIME)).append(" UTC\n\n");
        sb.append("Review and pay securely using this link (PayPal Sandbox):\n");
        sb.append(paymentUrl).append("\n\n");
        sb.append("Thank you,\n").append(sellerName).append(" via PayPilot AI\n");
        return sb.toString();
    }

    private String formatAmountWithCurrency(BigDecimal amount, String currency) {
        BigDecimal scaled = amount != null ? amount.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2);
        String code = currency != null ? currency.trim().toUpperCase(Locale.ROOT) : "USD";
        return scaled.toPlainString() + " " + code;
    }

    private String sanitizeMailFailure(String raw) {
        if (raw == null || raw.isBlank()) {
            return "SMTP transmission failed.";
        }
        String singleLine = raw.replaceAll("[\\r\\n]+", " ").trim();
        return singleLine.length() > 300 ? singleLine.substring(0, 300) : singleLine;
    }

    private String resolveDefaultOrigin(String allowedOriginsRaw) {
        if (allowedOriginsRaw == null || allowedOriginsRaw.isBlank()) {
            return "http://localhost:5173";
        }
        return Arrays.stream(allowedOriginsRaw.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .findFirst()
            .orElse("http://localhost:5173");
    }

    private record PreparedEmailContext(
        Long invoiceId,
        Long sellerUserId,
        Long paymentLinkId,
        String tokenPrefix,
        String recipientEmail,
        String recipientName,
        String subject,
        String htmlBody,
        String textBody,
        String paymentUrl,
        LocalDateTime linkExpiresAt
    ) {
    }
}

