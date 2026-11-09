package com.paypilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * PayPilot AI — Spring Boot Application Entry Point
 *
 * Architecture:
 *   - This service is the ONLY service that calls PayPal APIs.
 *   - All payment operations require explicit human approval before execution.
 *   - The AI service (Node.js) is an internal dependency only — never exposed to the browser.
 *
 * PayPal AI Hackathon 2026
 * Java 22.0.1 | Spring Boot 3.3.5
 */
@SpringBootApplication
public class PayPilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(PayPilotApplication.class, args);
    }
}

