package com.paypilot.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhoneNumberValidatorTest {

    @Test
    void normalizesValidInternationalE164Numbers() {
        assertThat(PhoneNumberValidator.normalizeOptional(null)).isNull();
        assertThat(PhoneNumberValidator.normalizeOptional("   ")).isNull();
        assertThat(PhoneNumberValidator.normalizeOptional("+1 (415) 555-2671"))
            .isEqualTo("+14155552671");
        assertThat(PhoneNumberValidator.normalizeOptional(" +91 98765 43210 "))
            .isEqualTo("+919876543210");
        assertThat(PhoneNumberValidator.digitsOnlyForWhatsApp("+1 (415) 555-2671"))
            .isEqualTo("14155552671");
    }

    @Test
    void rejectsMissingCountryCodeOrMalformedNumbers() {
        assertThatThrownBy(() -> PhoneNumberValidator.normalizeOptional("4155552671"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("+");

        assertThatThrownBy(() -> PhoneNumberValidator.normalizeOptional("+01234567"))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> PhoneNumberValidator.normalizeOptional("+12345"))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> PhoneNumberValidator.normalizeOptional("+1415abc2671"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}

