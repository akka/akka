/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import akka.actor.typed.Behavior
import akka.persistence.typed.PersistenceId

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

trait ActiveActiveContext {
  def timestamp: Long
  def origin: String
  def concurrent: Boolean
  // FIXME to generic name
  def replicaId: String
  def allReplicas: Set[String]
  def persistenceId: PersistenceId
  def recoveryRunning: Boolean
  def id: String
  def currentTimeMillis(): Long
}

// FIXME, parts of this can be set during initialisation
// Other fields will be set before executing the event handler as they change per event
private[akka] class ActiveActiveContextImpl(val id: String, val replicaId: String, val allReplicas: Set[String])
    extends ActiveActiveContext {
  var _timestamp: Long = -1
  var _origin: String = null
  var _concurrent: Boolean = false

  // FIXME check illegal access

  /**
   * The timestamp of the event. Always increases per data center
   * Undefined result if called from any where other than an event handler.
   */
  override def timestamp: Long = _timestamp

  /**
   * The origin of the current event.
   * Undefined result if called from anywhere other than an event handler.
   */
  override def origin: String = _origin

  /**
   * Whether the happened concurrently with an event from another replica.
   * Undefined result if called from any where other than an event handler.
   */
  override def concurrent: Boolean = _concurrent
  override def persistenceId: PersistenceId = PersistenceId.replicated(id, replicaId)
  override def currentTimeMillis(): Long = {
    // FIXME always increasing
    System.currentTimeMillis()
  }
  override def recoveryRunning: Boolean = false
}

object ActiveActiveEventSourcing {

  /**
   */
  def apply[Command, Event, State](id: String, replicaId: String, allReplicaIds: Set[String])(
      activeActiveContext: ActiveActiveContext => EventSourcedBehavior[Command, Event, State]): Behavior[Command] = {
    val context = new ActiveActiveContextImpl(id, replicaId, allReplicaIds)
    activeActiveContext(context).withActiveActive(context, replicaId, allReplicaIds)
  }

}
