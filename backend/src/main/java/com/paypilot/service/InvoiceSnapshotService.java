package com.paypilot.service;

import com.paypilot.model.Invoice;
import org.springframework.stereotype.Service;

import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Computes a deterministic SHA-256 hash over all material invoice fields.
 * Ties explicit user approval and PayPal order creation to the exact reviewed invoice state.
 */
@Service
public class InvoiceSnapshotService {

    public String computeHash(Invoice invoice) {
        if (invoice == null || invoice.getId() == null) {
            throw new IllegalStateException("Cannot compute snapshot hash for an unpersisted invoice.");
        }

        StringBuilder canonical = new StringBuilder();
        appendField(canonical, "id", String.valueOf(invoice.getId()));
        appendField(canonical, "invoiceNumber", nullSafeTrim(invoice.getInvoiceNumber()));
        appendField(canonical, "clientName", nullSafeTrim(invoice.getClientName()));
        appendField(canonical, "clientEmail", nullSafeTrim(invoice.getClientEmail()).toLowerCase(Locale.ROOT));
        appendField(canonical, "description", nullSafeTrim(invoice.getDescription()));
        appendField(canonical, "amount", invoice.getAmount() != null
            ? invoice.getAmount().setScale(2, RoundingMode.HALF_UP).toPlainString()
            : "");
        appendField(canonical, "currency", nullSafeTrim(invoice.getCurrency()).toUpperCase(Locale.ROOT));
        appendField(canonical, "dueDate", invoice.getDueDate() != null ? invoice.getDueDate().toString() : "");

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm unavailable.", ex);
        }
    }

    public boolean hashesMatch(String expectedHash, String submittedHash) {
        if (expectedHash == null || submittedHash == null) {
            return false;
        }
        byte[] expectedBytes = expectedHash.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        byte[] submittedBytes = submittedHash.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, submittedBytes);
    }

    private void appendField(StringBuilder target, String name, String value) {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        target.append(name).append('=').append(utf8.length).append(':').append(value).append('\n');
    }

    private String nullSafeTrim(String value) {
        return value == null ? "" : value.trim();
    }
}

