/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;
import org.opensearch.client.Request;
import org.opensearch.client.Response;

/**
 * Utility for logging PPL query requests and responses to a JSON Lines file. Enable by setting the
 * system property: -Dppl.query.logger.output=/path/to/output.jsonl
 */
public class PPLQueryLogger {
  private static final Logger LOG = LogManager.getLogger();
  private static final String OUTPUT_FILE_PROPERTY = "ppl.query.logger.output";
  private static final Gson GSON =
      new GsonBuilder().create(); // No pretty printing for JSON Lines format
  private static final PPLQueryLogger INSTANCE = new PPLQueryLogger();

  private final String outputFile;
  private final boolean enabled;

  private PPLQueryLogger() {
    this.outputFile = System.getProperty(OUTPUT_FILE_PROPERTY);
    this.enabled = outputFile != null && !outputFile.isEmpty();

    if (enabled) {
      LOG.info("PPL Query logging enabled. Output file: {}", outputFile);
      // Initialize output directory
      try {
        if (Paths.get(outputFile).getParent() != null) {
          Files.createDirectories(Paths.get(outputFile).getParent());
        }
      } catch (IOException e) {
        LOG.error("Failed to create output directory", e);
      }
    }
  }

  public static PPLQueryLogger getInstance() {
    return INSTANCE;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void logQueryAndResponse(Request request, Response response, String responseBody) {
    if (!enabled || !shouldLog(request)) {
      return;
    }

    try {
      // Extract query from request
      String requestBody = EntityUtils.toString(request.getEntity(), StandardCharsets.UTF_8);
      JSONObject requestJson = new JSONObject(requestBody);
      String query = requestJson.optString("query", "");

      // Build log entry
      JsonObject logEntry = new JsonObject();
      logEntry.addProperty("timestamp", Instant.now().toString());
      logEntry.addProperty("endpoint", request.getEndpoint());
      logEntry.addProperty("query", query);
      logEntry.addProperty("status_code", response.getStatusLine().getStatusCode());

      // Detect response format by endpoint or content
      boolean isCsvResponse = request.getEndpoint().contains("format=csv");

      if (isCsvResponse) {
        // CSV response - store as raw string
        logEntry.addProperty("format", "csv");
        logEntry.addProperty("response", responseBody);
      } else {
        // Try to parse as JSON
        try {
          JSONObject responseJson = new JSONObject(responseBody);
          logEntry.addProperty("format", "json");

          // Parse response - handle different response formats
          if (responseJson.has("datarows")) {
            // Standard PPL response format - store as JSON strings to avoid Gson wrapping
            logEntry.addProperty("datarows", responseJson.getJSONArray("datarows").toString());
            if (responseJson.has("schema")) {
              logEntry.addProperty("schema", responseJson.getJSONArray("schema").toString());
            }
          } else if (responseJson.has("results")) {
            // Alternative format
            logEntry.addProperty("results", responseJson.get("results").toString());
          } else {
            // Raw response
            logEntry.addProperty("response", responseBody);
          }
        } catch (Exception jsonEx) {
          // Not JSON - store as raw
          logEntry.addProperty("format", "raw");
          logEntry.addProperty("response", responseBody);
        }
      }

      // Append to file (JSON Lines format - one JSON object per line)
      synchronized (PPLQueryLogger.class) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile, true))) {
          writer.write(GSON.toJson(logEntry));
          writer.newLine();
        }
      }

    } catch (Exception e) {
      LOG.error("Failed to log query/response", e);
    }
  }

  private boolean shouldLog(Request request) {
    String endpoint = request.getEndpoint();
    // Only log PPL query endpoints (not explain, etc.)
    return endpoint.contains("/_plugins/_ppl")
        && !endpoint.contains("/_explain")
        && request.getMethod().equals("POST");
  }
}
