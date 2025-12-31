/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.plugin.lookup.poc.model;

import java.io.IOException;
import org.opensearch.core.action.ActionResponse;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.core.xcontent.ToXContentObject;
import org.opensearch.core.xcontent.XContentBuilder;

/** Response from storing lookup data. Contains lookup name and version (UUID). */
public class StoreLookupResponse extends ActionResponse implements ToXContentObject {

  private String lookupName;
  private String version;
  private int rowCount;

  public StoreLookupResponse(String lookupName, String version, int rowCount) {
    this.lookupName = lookupName;
    this.version = version;
    this.rowCount = rowCount;
  }

  public StoreLookupResponse(StreamInput in) throws IOException {
    super(in);
    this.lookupName = in.readString();
    this.version = in.readString();
    this.rowCount = in.readInt();
  }

  @Override
  public void writeTo(StreamOutput out) throws IOException {
    out.writeString(lookupName);
    out.writeString(version);
    out.writeInt(rowCount);
  }

  @Override
  public XContentBuilder toXContent(XContentBuilder builder, ToXContent.Params params)
      throws IOException {
    builder.startObject();
    builder.field("lookup_name", lookupName);
    builder.field("version", version);
    builder.field("row_count", rowCount);
    builder.endObject();
    return builder;
  }

  public String getLookupName() {
    return lookupName;
  }

  public String getVersion() {
    return version;
  }

  public int getRowCount() {
    return rowCount;
  }
}
