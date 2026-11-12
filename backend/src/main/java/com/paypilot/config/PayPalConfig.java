package com.paypilot.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Clock;

/**
 * Configures the dedicated PayPal Sandbox RestClient with fail-closed URL validation
 * and explicit HTTP connection/read timeouts.
 */
@Configuration
@EnableConfigurationProperties(PayPalProperties.class)
public class PayPalConfig {

    @Bean("payPalRestClient")
    public RestClient payPalRestClient(PayPalProperties properties) {
        String canonicalBaseUrl = properties.canonicalBaseUrl();

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeoutMs());
        requestFactory.setReadTimeout(properties.getReadTimeoutMs());

        return RestClient.builder()
            .baseUrl(canonicalBaseUrl)
            .requestFactory(requestFactory)
            .build();
    }

    @Bean("payPalClock")
    @ConditionalOnMissingBean(name = "payPalClock")
    public Clock payPalClock() {
        return Clock.systemUTC();
    }

    @Bean("paymentClock")
    @ConditionalOnMissingBean(name = "paymentClock")
    public Clock paymentClock() {
        return Clock.systemDefaultZone();
    }
}

