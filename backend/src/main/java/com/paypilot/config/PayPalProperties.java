package com.paypilot.config;

import com.paypilot.exception.PayPalConfigurationException;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.Locale;

/**
 * Backend-only PayPal Sandbox configuration properties.
 * Credentials are never logged, serialized, or exposed via API DTOs.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "paypal")
public class PayPalProperties {

    public static final String SANDBOX_MODE = "sandbox";
    public static final String SANDBOX_HOST = "api-m.sandbox.paypal.com";
    public static final String SANDBOX_BASE_URL = "https://api-m.sandbox.paypal.com";

    private String clientId;
    private String clientSecret;
    private String mode = SANDBOX_MODE;
    private String baseUrl = SANDBOX_BASE_URL;
    private String webhookId = "";
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 15000;
    private long tokenRefreshBufferSeconds = 60L;

    /**
     * Enforces strict PayPal Sandbox-only execution and fails closed on non-sandbox configuration.
     */
    public void validateSandboxConfiguration() {
        if (mode == null || !SANDBOX_MODE.equalsIgnoreCase(mode.trim())) {
            throw new PayPalConfigurationException(
                "PayPal Sandbox enforcement failed: PAYPAL_MODE must be set to 'sandbox'."
            );
        }

        if (baseUrl == null || baseUrl.isBlank()) {
            throw new PayPalConfigurationException(
                "PayPal Sandbox enforcement failed: PAYPAL_BASE_URL must be configured for Sandbox."
            );
        }

        URI parsed;
        try {
            parsed = URI.create(baseUrl.trim());
        } catch (IllegalArgumentException ex) {
            throw new PayPalConfigurationException(
                "PayPal Sandbox enforcement failed: invalid PAYPAL_BASE_URL syntax."
            );
        }

        String scheme = parsed.getScheme();
        String host = parsed.getHost();
        String path = parsed.getPath();

        boolean validScheme = "https".equalsIgnoreCase(scheme);
        boolean validHost = host != null && SANDBOX_HOST.equals(host.toLowerCase(Locale.ROOT));
        boolean validUserInfo = parsed.getRawUserInfo() == null;
        boolean validPort = parsed.getPort() == -1;
        boolean validPath = path == null || path.isEmpty() || "/".equals(path);
        boolean validQueryAndFragment = parsed.getRawQuery() == null && parsed.getRawFragment() == null;

        if (!validScheme || !validHost || !validUserInfo || !validPort || !validPath || !validQueryAndFragment) {
            throw new PayPalConfigurationException(
                "PayPal Sandbox enforcement failed: PAYPAL_BASE_URL must strictly point to " + SANDBOX_BASE_URL + "."
            );
        }

        if (connectTimeoutMs <= 0 || readTimeoutMs <= 0 || tokenRefreshBufferSeconds < 0) {
            throw new PayPalConfigurationException(
                "PayPal Sandbox timeout and token refresh configuration must be positive."
            );
        }
    }

    public String canonicalBaseUrl() {
        validateSandboxConfiguration();
        return SANDBOX_BASE_URL;
    }

    public void validateCredentialsPresent() {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new PayPalConfigurationException(
                "PayPal Sandbox client credentials are not configured."
            );
        }
    }

    @Override
    public String toString() {
        return "PayPalProperties{"
            + "mode='" + mode + '\''
            + ", baseUrl='" + baseUrl + '\''
            + ", clientIdConfigured=" + (clientId != null && !clientId.isBlank())
            + ", clientSecret='[REDACTED]'"
            + ", connectTimeoutMs=" + connectTimeoutMs
            + ", readTimeoutMs=" + readTimeoutMs
            + ", tokenRefreshBufferSeconds=" + tokenRefreshBufferSeconds
            + '}';
    }
}

