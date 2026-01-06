/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.common;

/**
 * POC: Static holder for LookupStoragePoc instance to avoid Guice circular dependency. This allows
 * CalciteRelNodeVisitor (in core module) to access LookupStoragePoc (created in plugin module)
 * without complex injection. Production implementation should use proper dependency injection.
 */
public class LookupStorageHolder {

  private static volatile Object lookupStorage;

  /**
   * Set the lookup storage instance. Called by SQLPlugin during initialization.
   *
   * @param storage The LookupStoragePoc instance (as Object to avoid circular dependency)
   */
  public static void setLookupStorage(Object storage) {
    lookupStorage = storage;
  }

  /**
   * Get the lookup storage instance. Returns null if not yet initialized.
   *
   * @return The LookupStoragePoc instance or null
   */
  public static Object getLookupStorage() {
    return lookupStorage;
  }

  /** Reset the lookup storage (for testing). */
  public static void reset() {
    lookupStorage = null;
  }
}
