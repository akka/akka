/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.javadsl

import java.util.function.{ Function => JFunction }
import java.util.{ Set => JSet }
import java.util.{ Map => JMap }

import akka.annotation.DoNotInherit
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.ReplicaId
import akka.persistence.typed.internal.ActiveActiveContextImpl

import akka.util.ccompat.JavaConverters._

/**
 * Provides access to Active Active specific state
 *
 * Not for user extension
 */
@DoNotInherit
trait ActiveActiveContext {
  def origin: ReplicaId
  def concurrent: Boolean
  def replicaId: ReplicaId
  def getAllReplicas: JSet[ReplicaId]
  def persistenceId: PersistenceId
  def recoveryRunning: Boolean
  def entityId: String
  def currentTimeMillis(): Long
}

/**
 * Factory to create an instance of an ActiveActiveEventSourcedBehavior
 */
@FunctionalInterface
trait ActiveActiveBehaviorFactory[Command, Event, State] {
  def apply(aaContext: ActiveActiveContext): ActiveActiveEventSourcedBehavior[Command, Event, State]
}

object ActiveActiveEventSourcing {

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
      allReplicaIds: JSet[ReplicaId],
      queryPluginId: String,
      behaviorFactory: JFunction[ActiveActiveContext, EventSourcedBehavior[Command, Event, State]])
      : EventSourcedBehavior[Command, Event, State] =
    create(entityId, replicaId, allReplicaIds.asScala.map(id => id -> queryPluginId).toMap.asJava, behaviorFactory)

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
  def create[Command, Event, State](
      entityId: String,
      replicaId: ReplicaId,
      allReplicasAndQueryPlugins: JMap[ReplicaId, String],
      eventSourcedBehaviorFactory: JFunction[ActiveActiveContext, EventSourcedBehavior[Command, Event, State]])
      : EventSourcedBehavior[Command, Event, State] = {
    val context = new ActiveActiveContextImpl(entityId, replicaId, allReplicasAndQueryPlugins.asScala.toMap)
    eventSourcedBehaviorFactory(context)
  }

}
