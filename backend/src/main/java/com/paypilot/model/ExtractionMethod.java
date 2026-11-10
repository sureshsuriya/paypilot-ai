package com.paypilot.model;

/**
 * Extraction technique used when parsing an uploaded invoice PDF.
 * TEXT is prioritized in MVP; OCR is reserved for future scanned PDF support.
 */
public enum ExtractionMethod {
    TEXT,
    OCR
}

