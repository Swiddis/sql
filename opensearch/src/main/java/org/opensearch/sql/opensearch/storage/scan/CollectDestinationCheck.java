/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.opensearch.storage.scan;

import java.util.Map;
import java.util.Optional;
import org.opensearch.action.admin.indices.settings.get.GetSettingsResponse;
import org.opensearch.action.support.IndicesOptions;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.common.settings.Settings;
import org.opensearch.indices.SystemIndexRegistry;
import org.opensearch.sql.exception.SemanticCheckException;
import org.opensearch.sql.opensearch.client.OpenSearchClient;
import org.opensearch.transport.client.node.NodeClient;

/**
 * Refuses a {@code collect} destination that resolves to a system or hidden index. The name is
 * resolved through the cluster the way the bulk write will resolve it (aliases, date math), so an
 * alias or date-math name cannot reach a hidden index that its own spelling does not reveal.
 */
public final class CollectDestinationCheck {

  /** One concrete index or an alias; expand hidden ones too so they can be seen and refused. */
  private static final IndicesOptions OPTIONS =
      IndicesOptions.fromOptions(false, false, true, true, true);

  private CollectDestinationCheck() {}

  public static void check(OpenSearchClient client, String name) {
    refuseIfSystemOrHidden(name, name, Settings.EMPTY);
    Optional<NodeClient> node = client.getNodeClient();
    if (node.isEmpty()) {
      // shortcut: the REST client (standalone tests) skips cluster resolution; only the
      // dot-prefix and system-pattern checks on the name apply there.
      return;
    }
    GetSettingsResponse response =
        node.get()
            .admin()
            .indices()
            .prepareGetSettings(name)
            .setIndicesOptions(OPTIONS)
            .setNames(IndexMetadata.SETTING_INDEX_HIDDEN)
            .get();
    for (Map.Entry<String, Settings> e : response.getIndexToSettings().entrySet()) {
      refuseIfSystemOrHidden(name, e.getKey(), e.getValue());
    }
  }

  private static void refuseIfSystemOrHidden(String name, String index, Settings settings) {
    boolean hidden = settings.getAsBoolean(IndexMetadata.SETTING_INDEX_HIDDEN, false);
    if (index.startsWith(".") || hidden || SystemIndexRegistry.matchesSystemIndexPattern(index)) {
      throw new SemanticCheckException(
          String.format(
              "collect cannot write to system or hidden index [%s]%s",
              index, index.equals(name) ? "" : " (resolved from [" + name + "])"));
    }
  }
}
