/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.plugin.lookup.poc.model;

import java.io.IOException;
import org.opensearch.action.ActionRequest;
import org.opensearch.action.ActionRequestValidationException;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;

/** Request to get lookup metadata from registry. Contains only the lookup name. */
public class GetLookupRequest extends ActionRequest {

  private String lookupName;

  public GetLookupRequest(String lookupName) {
    this.lookupName = lookupName;
  }

  public GetLookupRequest(StreamInput in) throws IOException {
    super(in);
    this.lookupName = in.readString();
  }

  @Override
  public void writeTo(StreamOutput out) throws IOException {
    super.writeTo(out);
    out.writeString(lookupName);
  }

  @Override
  public ActionRequestValidationException validate() {
    if (lookupName == null || lookupName.trim().isEmpty()) {
      ActionRequestValidationException validationException = new ActionRequestValidationException();
      validationException.addValidationError("lookup_name is required");
      return validationException;
    }
    return null;
  }

  public String getLookupName() {
    return lookupName;
  }
}
