package io.github.rajeshponna.localai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.camunda.connector.api.annotation.OutboundConnector;
import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.api.outbound.OutboundConnectorContext;
import io.camunda.connector.api.outbound.OutboundConnectorFunction;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One connector for local AI in Camunda workflows. Works with Ollama, vLLM, and any
 * server that exposes the OpenAI-compatible /v1/chat/completions API.
 *
 * Output: answer, json (JSON mode only), promptTokens, completionTokens, totalTokens,
 * durationMs, model, provider, finishReason.
 */
@OutboundConnector(
    name = "Local AI Chat",
    inputVariables = {
      "provider", "baseUrl", "apiKey", "model", "systemPrompt", "userPrompt",
      "temperature", "maxTokens", "outputMode", "jsonSchema", "timeoutSeconds"
    },
    type = "io.github.rajeshponna:local-ai-chat:1")
public class LocalAiConnectorFunction implements OutboundConnectorFunction {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int DEFAULT_TIMEOUT_SECONDS = 120;

  // Local AI servers (vLLM, Ollama) speak HTTP/1.1, so skip Java's default HTTP/2 upgrade
  private final HttpClient http =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(10))
          .build();

  @Override
  public Object execute(OutboundConnectorContext context) throws Exception {
    LocalAiRequest req = context.bindVariables(LocalAiRequest.class);
    validate(req);

    String provider = providerLabel(req.provider());
    boolean jsonMode = "json".equalsIgnoreCase(trim(req.outputMode()));
    String requestJson = MAPPER.writeValueAsString(buildBody(req, jsonMode));
    String url = stripTrailingSlash(req.baseUrl().trim()) + "/v1/chat/completions";
    int timeout = parseInt(req.timeoutSeconds(), DEFAULT_TIMEOUT_SECONDS, "timeoutSeconds");

    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(timeout))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestJson));
    if (notBlank(req.apiKey())) {
      builder.header("Authorization", "Bearer " + req.apiKey().trim());
    }

    long start = System.currentTimeMillis();
    HttpResponse<String> response;
    try {
      response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    } catch (HttpTimeoutException e) {
      throw new ConnectorException(
          "AI_TIMEOUT", provider + " did not respond within " + timeout + " seconds at " + url);
    } catch (IOException e) {
      // Include the exception type, since some network errors have no message
      throw new ConnectorException(
          "AI_UNREACHABLE",
          "Could not reach " + provider + " at " + url + ": " + e.getClass().getSimpleName()
              + (e.getMessage() != null ? " - " + e.getMessage() : ""));
    }
    long durationMs = System.currentTimeMillis() - start;

    if (response.statusCode() != 200) {
      throw new ConnectorException(
          "AI_HTTP_" + response.statusCode(),
          provider + " API error: HTTP " + response.statusCode() + " - " + response.body());
    }

    JsonNode json = MAPPER.readTree(response.body());
    JsonNode choice = json.path("choices").path(0);
    String answer = choice.path("message").path("content").asText("");
    JsonNode usage = json.path("usage");

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("answer", answer);
    if (jsonMode) {
      result.put("json", parseJsonAnswer(answer));
    }
    result.put("promptTokens", usage.path("prompt_tokens").asInt(0));
    result.put("completionTokens", usage.path("completion_tokens").asInt(0));
    result.put("totalTokens", usage.path("total_tokens").asInt(0));
    result.put("durationMs", durationMs);
    result.put("model", json.path("model").asText(""));
    result.put("provider", trim(req.provider()));
    result.put("finishReason", choice.path("finish_reason").asText(""));
    return result;
  }

  private ObjectNode buildBody(LocalAiRequest req, boolean jsonMode) {
    ObjectNode body = MAPPER.createObjectNode();
    body.put("model", req.model().trim());
    body.put("stream", false);

    ArrayNode messages = body.putArray("messages");
    if (notBlank(req.systemPrompt())) {
      messages.addObject().put("role", "system").put("content", req.systemPrompt());
    }
    messages.addObject().put("role", "user").put("content", req.userPrompt());

    if (notBlank(req.temperature())) {
      body.put("temperature", parseDouble(req.temperature(), "temperature"));
    }
    if (notBlank(req.maxTokens())) {
      body.put("max_tokens", parseInt(req.maxTokens(), 0, "maxTokens"));
    }

    if (jsonMode) {
      ObjectNode format = body.putObject("response_format");
      if (notBlank(req.jsonSchema())) {
        // The server forces the answer to match this schema
        format.put("type", "json_schema");
        ObjectNode schemaWrapper = format.putObject("json_schema");
        schemaWrapper.put("name", "output");
        schemaWrapper.set("schema", parseSchema(req.jsonSchema()));
      } else {
        // Any valid JSON object
        format.put("type", "json_object");
      }
    }
    return body;
  }

  private Object parseJsonAnswer(String answer) {
    try {
      return MAPPER.readValue(answer, Object.class);
    } catch (IOException e) {
      throw new ConnectorException("AI_INVALID_JSON", "Model output is not valid JSON: " + answer);
    }
  }

  private JsonNode parseSchema(String schema) {
    try {
      return MAPPER.readTree(schema);
    } catch (IOException e) {
      throw new ConnectorException(
          "AI_INVALID_SCHEMA", "JSON schema is not valid JSON: " + e.getMessage());
    }
  }

  private void validate(LocalAiRequest req) {
    if (req == null) {
      throw new ConnectorException("AI_INVALID_INPUT", "No input provided");
    }
    if (!notBlank(req.baseUrl())) {
      throw new ConnectorException("AI_INVALID_INPUT", "Base URL must not be empty");
    }
    if (!notBlank(req.model())) {
      throw new ConnectorException("AI_INVALID_INPUT", "Model must not be empty");
    }
    if (!notBlank(req.userPrompt())) {
      throw new ConnectorException("AI_INVALID_INPUT", "User prompt must not be empty");
    }
  }

  private static String providerLabel(String provider) {
    return switch (trim(provider).toLowerCase()) {
      case "ollama" -> "Ollama";
      case "vllm" -> "vLLM";
      default -> "AI server";
    };
  }

  private static int parseInt(String value, int defaultValue, String field) {
    if (!notBlank(value)) {
      return defaultValue;
    }
    try {
      return (int) Double.parseDouble(value.trim());
    } catch (NumberFormatException e) {
      throw new ConnectorException("AI_INVALID_INPUT", field + " must be a number, got: " + value);
    }
  }

  private static double parseDouble(String value, String field) {
    try {
      return Double.parseDouble(value.trim());
    } catch (NumberFormatException e) {
      throw new ConnectorException("AI_INVALID_INPUT", field + " must be a number, got: " + value);
    }
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }

  private static String stripTrailingSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
