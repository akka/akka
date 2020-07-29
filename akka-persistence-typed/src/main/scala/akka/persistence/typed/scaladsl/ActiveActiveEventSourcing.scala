/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import akka.persistence.typed.PersistenceId
import akka.persistence.typed.ReplicaId
import akka.util.{ OptionVal, WallClock }

import akka.util.ccompat.JavaConverters._

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
    extends ActiveActiveContext
    with akka.persistence.typed.javadsl.ActiveActiveContext {
  val allReplicas: Set[ReplicaId] = replicasAndQueryPlugins.keySet

  // these are not volatile as they are set on the same thread as they should be accessed
  var _origin: ReplicaId = null
  var _recoveryRunning: Boolean = false
  var _concurrent: Boolean = false
  var _currentThread: OptionVal[Thread] = OptionVal.None

  private def checkAccess(functionName: String): Unit = {
    val callerThread = Thread.currentThread()
    def error() =
      throw new UnsupportedOperationException(
        s"Unsupported access to ActiveActiveContext operation from the outside of event handler. " +
        s"$functionName can only be called from the event handler")
    _currentThread match {
      case OptionVal.Some(t) =>
        if (callerThread ne t) error()
      case OptionVal.None =>
        error()
    }
  }

  /**
   * The origin of the current event.
   * Undefined result if called from anywhere other than an event handler.
   */
  override def origin: ReplicaId = {
    checkAccess("origin")
    _origin
  }

  /**
   * Whether the happened concurrently with an event from another replica.
   * Undefined result if called from any where other than an event handler.
   */
  override def concurrent: Boolean = {
    checkAccess("concurrent")
    _concurrent
  }

  override def persistenceId: PersistenceId = PersistenceId.replicatedUniqueId(entityId, replicaId)

  override def currentTimeMillis(): Long = {
    WallClock.AlwaysIncreasingClock.currentTimeMillis()
  }
  override def recoveryRunning: Boolean = {
    checkAccess("recoveryRunning")
    _recoveryRunning
  }

  override def getAllReplicas: java.util.Set[ReplicaId] = allReplicas.asJava
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
      queryPluginId: String)(
      eventSourcedBehaviorFactory: ActiveActiveContext => EventSourcedBehavior[Command, Event, State])
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
      eventSourcedBehaviorFactory: ActiveActiveContext => EventSourcedBehavior[Command, Event, State])
      : EventSourcedBehavior[Command, Event, State] = {
    val context = new ActiveActiveContextImpl(entityId, replicaId, allReplicasAndQueryPlugins)
    eventSourcedBehaviorFactory(context).withActiveActive(context)
  }

}
