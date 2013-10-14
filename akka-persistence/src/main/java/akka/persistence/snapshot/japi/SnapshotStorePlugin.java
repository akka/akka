/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.snapshot.japi;

import scala.concurrent.Future;

import akka.japi.Option;
import akka.persistence.*;

interface SnapshotStorePlugin {
    //#snapshot-store-plugin-api
    /**
     * Plugin Java API.
     *
     * Asynchronously loads a snapshot.
     *
     * @param processorId processor id.
     * @param criteria selection criteria for loading.
     */
    Future<Option<SelectedSnapshot>> doLoadAsync(String processorId, SnapshotSelectionCriteria criteria);

    /**
     * Plugin Java API.
     *
     * Asynchronously saves a snapshot.
     *
     * @param metadata snapshot metadata.
     * @param snapshot snapshot.
     */
    Future<Void> doSaveAsync(SnapshotMetadata metadata, Object snapshot);

    /**
     * Plugin Java API.
     *
     * Called after successful saving of a snapshot.
     *
     * @param metadata snapshot metadata.
     */
    void onSaved(SnapshotMetadata metadata) throws Exception;

    /**
     * Plugin Java API.
     *
     * Deletes the snapshot identified by `metadata`.
     *
     * @param metadata snapshot metadata.
     */
    void doDelete(SnapshotMetadata metadata) throws Exception;
    //#snapshot-store-plugin-api
}
