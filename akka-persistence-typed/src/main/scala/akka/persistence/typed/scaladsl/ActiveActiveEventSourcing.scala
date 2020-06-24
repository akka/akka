/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import akka.persistence.typed.{ PersistenceId, WallClock }

/**
 * Utility class for comparing timestamp and data center
 * identifier when implementing last-writer wins.
 */
final case class LwwTime(timestamp: Long, originDc: String) {

  /**
   * Create a new `LwwTime` that has a `timestamp` that is
   * `max` of the given timestamp and previous timestamp + 1,
   * i.e. monotonically increasing.
   */
  def increase(t: Long, replicaId: String): LwwTime =
    LwwTime(math.max(timestamp + 1, t), replicaId)

  /**
   * Compare this `LwwTime` with the `other`.
   * Greatest timestamp wins. If both timestamps are
   * equal the `dc` identifiers are compared and the
   * one sorted first in alphanumeric order wins.
   */
  def isAfter(other: LwwTime): Boolean = {
    if (timestamp > other.timestamp) true
    else if (timestamp < other.timestamp) false
    else if (other.originDc.compareTo(originDc) > 0) true
    else false
  }
}

// FIXME docs
trait ActiveActiveContext {

  def origin: String
  def concurrent: Boolean
  def replicaId: String
  def allReplicas: Set[String]
  def persistenceId: PersistenceId
  def recoveryRunning: Boolean
  def entityId: String
  def currentTimeMillis(): Long

}

// FIXME, parts of this can be set during initialisation
// Other fields will be set before executing the event handler as they change per event
// https://github.com/akka/akka/issues/29258
private[akka] class ActiveActiveContextImpl(val entityId: String, val replicaId: String, val allReplicas: Set[String])
    extends ActiveActiveContext {
  var _origin: String = null
  var _recoveryRunning = false

  // FIXME check illegal access https://github.com/akka/akka/issues/29264

  /**
   * The origin of the current event.
   * Undefined result if called from anywhere other than an event handler.
   */
  override def origin: String = _origin

  /**
   * Whether the happened concurrently with an event from another replica.
   * Undefined result if called from any where other than an event handler.
   */
  override def concurrent: Boolean = throw new UnsupportedOperationException("TODO")

  override def persistenceId: PersistenceId = PersistenceId.replicatedUniqueId(entityId, replicaId)

  override def currentTimeMillis(): Long = {
    WallClock.AlwaysIncreasingClock.currentTimeMillis()
  }
  override def recoveryRunning: Boolean = _recoveryRunning
}

object ActiveActiveEventSourcing {

  /**
   * Initialize a replicated event sourced behavior.
   *
   * Events from each replica for the same entityId will be replicated to every copy.
   * Care must be taken to handle events in any order as events can happen concurrently at different replicas.
   *
   * Using an replicated event sourced behavior means there is no longer the single writer guarantee.
   *
   * A different journal plugin id can be configured using withJournalPluginId after creation. Different databases
   * can be used for each replica.
   * The events from other replicas are read using PersistentQuery.
   * TODO support a different query plugin id per replicas: https://github.com/akka/akka/issues/29257
   *
   * @param replicaId The unique identity for this entity. The underlying persistence id will include the replica.
   * @param allReplicaIds All replica ids. These need to be known to receive events from all replicas.
   * @param queryPluginId Used to read the events from other replicas. Must be the query side of your configured journal plugin.
   * @return
   */
  def apply[Command, Event, State](
      entityId: String,
      replicaId: String,
      allReplicaIds: Set[String],
      queryPluginId: String)(activeActiveContext: ActiveActiveContext => EventSourcedBehavior[Command, Event, State])
      : EventSourcedBehavior[Command, Event, State] = {
    val context = new ActiveActiveContextImpl(entityId, replicaId, allReplicaIds)
    activeActiveContext(context).withActiveActive(context, replicaId, allReplicaIds, queryPluginId)
  }

}
