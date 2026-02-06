/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.opensearch.response.error;

import java.util.List;
import java.util.Map;
import lombok.Getter;
import org.json.JSONArray;
import org.json.JSONObject;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.sql.common.error.ErrorReport;

/** Error Message. */
public class ErrorMessage {

  protected final Throwable exception;

  private final int status;

  @Getter private final String type;

  @Getter private final String reason;

  @Getter private final String details;

  /** Error Message Constructor. */
  public ErrorMessage(Throwable exception, int status) {
    this.exception = exception;
    this.status = status;

    this.type = fetchType();
    this.reason = fetchReason();
    this.details = fetchDetails();
  }

  private String fetchType() {
    return exception.getClass().getSimpleName();
  }

  protected String fetchReason() {
    return status == RestStatus.BAD_REQUEST.getStatus()
        ? "Invalid Query"
        : "There was internal problem at backend";
  }

  protected String fetchDetails() {
    // Some exception prints internal information (full class name) which is security concern
    return emptyStringIfNull(exception.getLocalizedMessage());
  }

  private String emptyStringIfNull(String str) {
    return str != null ? str : "";
  }

  @Override
  public String toString() {
    JSONObject output = new JSONObject();

    output.put("status", status);
    output.put("error", getErrorAsJson());

    return output.toString(2);
  }

  private JSONObject getErrorAsJson() {
    JSONObject errorJson = new JSONObject();

    errorJson.put("type", type);
    errorJson.put("reason", reason);
    errorJson.put("details", details);

    // If this is an ErrorReport, add rich contextual information
    if (exception instanceof ErrorReport) {
      ErrorReport report = (ErrorReport) exception;

      // Add error code if available
      if (report.getCode() != null) {
        errorJson.put("code", report.getCode().name());
      }

      // Add location chain if available
      List<String> locationChain = report.getLocationChain();
      if (!locationChain.isEmpty()) {
        JSONArray locationArray = new JSONArray(locationChain);
        errorJson.put("location", locationArray);
      }

      // Add context data if available
      Map<String, Object> context = report.getContext();
      if (!context.isEmpty()) {
        JSONObject contextJson = new JSONObject();
        for (Map.Entry<String, Object> entry : context.entrySet()) {
          contextJson.put(entry.getKey(), entry.getValue());
        }
        errorJson.put("context", contextJson);
      }

      // Add suggestion if available
      if (report.getSuggestion() != null) {
        errorJson.put("suggestion", report.getSuggestion());
      }
    }

    return errorJson;
  }
}
