/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import akka.annotation.DoNotInherit
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.ReplicaId
import akka.persistence.typed.internal.ReplicationContextImpl

/**
 * Provides access to replication specific state
 *
 * Not for user extension
 */
@DoNotInherit
trait ReplicationContext {

  /**
   * @return The unique id of this replica, including the replica id
   */
  def persistenceId: PersistenceId

  /**
   * @return The replica id of this replicated event sourced actor
   */
  def replicaId: ReplicaId

  /**
   * @return The ids of all replicas of this replicated event sourced actor
   */
  def allReplicas: Set[ReplicaId]

  /**
   * @return The entity id of this replicated event sourced actor (not including the replica id)
   */
  def entityId: String

  /**
   * Must only be called from the event handler
   * @return the replica id where the current event was persisted
   */
  def origin: ReplicaId

  /**
   * Must only be called from the event handler
   * @return true if this event was concurrent with another event
   */
  def concurrent: Boolean

  /**
   * Must only be called from the event handler
   * @return true when the event handler is invoked during recovery.
   */
  def recoveryRunning: Boolean

  /**
   * @return a timestamp that will always be increasing (is monotonic)
   */
  def currentTimeMillis(): Long

}

object ReplicatedEventSourcing {

  /**
   * Initialize a replicated event sourced behavior where all entity replicas are stored in the same journal.
   *
   * Events from each replica for the same entityId will be replicated to every copy.
   * Care must be taken to handle events in any order as events can happen concurrently at different replicas.
   *
   * Using an replicated event sourced behavior means there is no longer the single writer guarantee.
   *
   * A different journal plugin id can be configured using withJournalPluginId after creation. Different databases
   * can be used for each replica.
   * The events from other replicas are read using PersistentQuery.
   *
   * @param replicaId The unique identity for this entity. The underlying persistence id will include the replica.
   * @param allReplicaIds All replica ids. These need to be known to receive events from all replicas.
   * @param queryPluginId A single query plugin used to read the events from other replicas. Must be the query side of your configured journal plugin.
   */
  def withSharedJournal[Command, Event, State](
      entityId: String,
      replicaId: ReplicaId,
      allReplicaIds: Set[ReplicaId],
      queryPluginId: String)(
      eventSourcedBehaviorFactory: ReplicationContext => EventSourcedBehavior[Command, Event, State])
      : EventSourcedBehavior[Command, Event, State] =
    apply(entityId, replicaId, allReplicaIds.map(id => id -> queryPluginId).toMap)(eventSourcedBehaviorFactory)

  /**
   * Initialize a replicated event sourced behavior.
   *
   * Events from each replica for the same entityId will be replicated to every copy.
   * Care must be taken to handle events in any order as events can happen concurrently at different replicas.
   *
   * Using an replicated event sourced behavior means there is no longer the single writer guarantee.
   *
   * The journal plugin id for the entity itself can be configured using withJournalPluginId after creation.
   * A query side identifier is passed per replica allowing for separate database/journal configuration per
   * replica. The events from other replicas are read using PersistentQuery.
   *
   * @param replicaId The unique identity for this entity. The underlying persistence id will include the replica.
   * @param allReplicasAndQueryPlugins All replica ids and a query plugin per replica id. These need to be known to receive events from all replicas
   *                                   and configured with the query plugin for the journal that each replica uses.
   */
  def apply[Command, Event, State](
      entityId: String,
      replicaId: ReplicaId,
      allReplicasAndQueryPlugins: Map[ReplicaId, String])(
      eventSourcedBehaviorFactory: ReplicationContext => EventSourcedBehavior[Command, Event, State])
      : EventSourcedBehavior[Command, Event, State] = {
    val context = new ReplicationContextImpl(entityId, replicaId, allReplicasAndQueryPlugins)
    eventSourcedBehaviorFactory(context).withReplication(context)
  }

}
