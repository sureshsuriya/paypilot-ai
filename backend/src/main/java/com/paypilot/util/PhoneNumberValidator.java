package com.paypilot.util;

import java.util.regex.Pattern;

/**
 * Normalizes and validates international customer phone numbers in strict E.164 format
 * so WhatsApp click-to-chat and customer records never accept ambiguous local-only numbers.
 */
public final class PhoneNumberValidator {

    private static final Pattern SEPARATOR_PATTERN = Pattern.compile("[\\s\\-().]+");
    private static final Pattern E164_PATTERN = Pattern.compile("^\\+[1-9]\\d{6,14}$");

    private PhoneNumberValidator() {
    }

    /**
     * Normalizes an optional phone input by stripping common formatting characters.
     *
     * @return normalized E.164 string (e.g. "+14155552671"), or null when blank/absent
     * @throws IllegalArgumentException if non-blank input does not conform to E.164
     */
    public static String normalizeOptional(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String compact = SEPARATOR_PATTERN.matcher(raw.trim()).replaceAll("");
        if (!E164_PATTERN.matcher(compact).matches()) {
            throw new IllegalArgumentException(
                "Phone number must include country code starting with + and 7 to 15 digits (for example, +14155552671)."
            );
        }
        return compact;
    }

    public static String digitsOnlyForWhatsApp(String normalizedE164) {
        String valid = normalizeOptional(normalizedE164);
        if (valid == null) {
            throw new IllegalArgumentException("A valid international phone number is required for WhatsApp sharing.");
        }
        return valid.substring(1);
    }
}

