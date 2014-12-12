/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.snapshot.japi;

import scala.concurrent.Future;

import akka.japi.Option; 
import akka.persistence.*;

interface SnapshotStorePlugin {
  //#snapshot-store-plugin-api
  /**
   * Java API, Plugin API: asynchronously loads a snapshot.
   *
   * @param persistenceId
   *          id of the persistent actor.
   * @param criteria
   *          selection criteria for loading.
   */
  Future<Option<SelectedSnapshot>> doLoadAsync(String persistenceId, SnapshotSelectionCriteria criteria);

  /**
   * Java API, Plugin API: asynchronously saves a snapshot.
   *
   * @param metadata
   *          snapshot metadata.
   * @param snapshot
   *          snapshot.
   */
  Future<Void> doSaveAsync(SnapshotMetadata metadata, Object snapshot);

  /**
   * Java API, Plugin API: called after successful saving of a snapshot.
   *
   * @param metadata
   *          snapshot metadata.
   */
  void onSaved(SnapshotMetadata metadata) throws Exception;

  /**
   * Java API, Plugin API: deletes the snapshot identified by `metadata`.
   *
   * @param metadata
   *          snapshot metadata.
   */
  void doDelete(SnapshotMetadata metadata) throws Exception;

  /**
   * Java API, Plugin API: deletes all snapshots matching `criteria`.
   *
   * @param persistenceId
   *          id of the persistent actor.
   * @param criteria
   *          selection criteria for deleting.
   */
  void doDelete(String persistenceId, SnapshotSelectionCriteria criteria) throws Exception;
  //#snapshot-store-plugin-api
}
