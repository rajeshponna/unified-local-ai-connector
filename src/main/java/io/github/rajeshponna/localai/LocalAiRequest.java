package io.github.rajeshponna.localai;

/**
 * Input variables for the Local AI Chat connector, bound from the element template.
 * Numeric settings are kept as strings and parsed in the connector, so empty
 * fields in the Modeler never cause binding errors.
 */
public record LocalAiRequest(
    String provider,
    String baseUrl,
    String apiKey,
    String model,
    String systemPrompt,
    String userPrompt,
    String temperature,
    String maxTokens,
    String outputMode,
    String jsonSchema,
    String timeoutSeconds) {}
