/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.plugin.lookup.poc.model;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.opensearch.action.ActionRequest;
import org.opensearch.action.ActionRequestValidationException;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;

/** Request to store lookup data. Contains lookup name, schema, and data rows to store. */
public class StoreLookupRequest extends ActionRequest {

  private String lookupName;
  private Map<String, String> schema;
  private List<Map<String, Object>> data;

  public StoreLookupRequest(
      String lookupName, Map<String, String> schema, List<Map<String, Object>> data) {
    this.lookupName = lookupName;
    this.schema = schema;
    this.data = data;
  }

  public StoreLookupRequest(StreamInput in) throws IOException {
    super(in);
    this.lookupName = in.readString();
    this.schema = in.readMap(StreamInput::readString, StreamInput::readString);
    this.data = in.readList(StreamInput::readMap);
  }

  @Override
  public void writeTo(StreamOutput out) throws IOException {
    super.writeTo(out);
    out.writeString(lookupName);
    out.writeMap(schema, StreamOutput::writeString, StreamOutput::writeString);
    out.writeCollection(data, StreamOutput::writeMap);
  }

  @Override
  public ActionRequestValidationException validate() {
    ActionRequestValidationException validationException = null;
    if (lookupName == null || lookupName.trim().isEmpty()) {
      validationException = new ActionRequestValidationException();
      validationException.addValidationError("lookup_name is required");
    }
    if (data == null || data.isEmpty()) {
      if (validationException == null) {
        validationException = new ActionRequestValidationException();
      }
      validationException.addValidationError("data is required and cannot be empty");
    }
    return validationException;
  }

  public String getLookupName() {
    return lookupName;
  }

  public Map<String, String> getSchema() {
    return schema;
  }

  public List<Map<String, Object>> getData() {
    return data;
  }
}
