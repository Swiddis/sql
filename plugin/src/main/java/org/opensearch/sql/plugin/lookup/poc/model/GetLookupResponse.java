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

/** Response containing lookup metadata from registry. Used internally for query resolution. */
public class GetLookupResponse extends ActionResponse implements ToXContentObject {

  private String lookupName;
  private String version;
  private String indexName;

  public GetLookupResponse(String lookupName, String version, String indexName) {
    this.lookupName = lookupName;
    this.version = version;
    this.indexName = indexName;
  }

  public GetLookupResponse(StreamInput in) throws IOException {
    super(in);
    this.lookupName = in.readString();
    this.version = in.readString();
    this.indexName = in.readString();
  }

  @Override
  public void writeTo(StreamOutput out) throws IOException {
    out.writeString(lookupName);
    out.writeString(version);
    out.writeString(indexName);
  }

  @Override
  public XContentBuilder toXContent(XContentBuilder builder, ToXContent.Params params)
      throws IOException {
    builder.startObject();
    builder.field("lookup_name", lookupName);
    builder.field("version", version);
    builder.field("index", indexName);
    builder.endObject();
    return builder;
  }

  public String getLookupName() {
    return lookupName;
  }

  public String getVersion() {
    return version;
  }

  public String getIndexName() {
    return indexName;
  }
}
