/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import akka.persistence.typed.PersistenceId
import akka.persistence.typed.ReplicaId
import akka.util.WallClock

// FIXME docs
trait ActiveActiveContext {

  def persistenceId: PersistenceId
  def replicaId: ReplicaId
  def allReplicas: Set[ReplicaId]
  def entityId: String

  def origin: ReplicaId
  def concurrent: Boolean
  def recoveryRunning: Boolean
  def currentTimeMillis(): Long

}

// FIXME, parts of this can be set during initialisation
// Other fields will be set before executing the event handler as they change per event
// https://github.com/akka/akka/issues/29258
private[akka] class ActiveActiveContextImpl(
    val entityId: String,
    val replicaId: ReplicaId,
    val replicasAndQueryPlugins: Map[ReplicaId, String])
    extends ActiveActiveContext {
  val allReplicas: Set[ReplicaId] = replicasAndQueryPlugins.keySet
  var _origin: ReplicaId = null
  var _recoveryRunning: Boolean = false
  var _concurrent: Boolean = false

  // FIXME check illegal access https://github.com/akka/akka/issues/29264

  /**
   * The origin of the current event.
   * Undefined result if called from anywhere other than an event handler.
   */
  override def origin: ReplicaId = _origin

  /**
   * Whether the happened concurrently with an event from another replica.
   * Undefined result if called from any where other than an event handler.
   */
  override def concurrent: Boolean = _concurrent

  override def persistenceId: PersistenceId = PersistenceId.replicatedUniqueId(entityId, replicaId)

  override def currentTimeMillis(): Long = {
    WallClock.AlwaysIncreasingClock.currentTimeMillis()
  }
  override def recoveryRunning: Boolean = _recoveryRunning
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
      allReplicaIds: Set[ReplicaId],
      queryPluginId: String)(activeActiveContext: ActiveActiveContext => EventSourcedBehavior[Command, Event, State])
      : EventSourcedBehavior[Command, Event, State] =
    apply(entityId, replicaId, allReplicaIds.map(id => id -> queryPluginId).toMap)(activeActiveContext)

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
      activeActiveContext: ActiveActiveContext => EventSourcedBehavior[Command, Event, State])
      : EventSourcedBehavior[Command, Event, State] = {
    val context = new ActiveActiveContextImpl(entityId, replicaId, allReplicasAndQueryPlugins)
    activeActiveContext(context).withActiveActive(context, replicaId, allReplicasAndQueryPlugins)
  }

}
