/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import akka.annotation.InternalApi
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.ReplicaId
import akka.util.OptionVal
import akka.util.WallClock
import akka.util.ccompat.JavaConverters._

/**
 * INTERNAL API
 */
// FIXME, parts of this can be set during initialisation
// Other fields will be set before executing the event handler as they change per event
// https://github.com/akka/akka/issues/29258
@InternalApi
private[akka] final class ReplicationContextImpl(
    val entityId: String,
    val replicaId: ReplicaId,
    val replicasAndQueryPlugins: Map[ReplicaId, String])
    extends akka.persistence.typed.scaladsl.ReplicationContext
    with akka.persistence.typed.javadsl.ReplicationContext {
  val allReplicas: Set[ReplicaId] = replicasAndQueryPlugins.keySet

  // these are not volatile as they are set on the same thread as they should be accessed
  var _currentThread: OptionVal[Thread] = OptionVal.None
  var _origin: OptionVal[ReplicaId] = OptionVal.None
  var _recoveryRunning: Boolean = false
  var _concurrent: Boolean = false

  private def checkAccess(functionName: String): Unit = {
    val callerThread = Thread.currentThread()
    def error() =
      throw new UnsupportedOperationException(
        s"Unsupported access to ReplicationContext operation from the outside of event handler. " +
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
    _origin match {
      case OptionVal.Some(origin) => origin
      case OptionVal.None         => throw new IllegalStateException("origin can only be accessed from the event handler")
    }
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

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final case class ReplicationSetup(
    replicaId: ReplicaId,
    allReplicasAndQueryPlugins: Map[ReplicaId, String],
    replicationContext: ReplicationContextImpl) {

  val allReplicas: Set[ReplicaId] = allReplicasAndQueryPlugins.keySet

  /**
   * Must only be called on the same thread that will execute the user code
   */
  def setContext(recoveryRunning: Boolean, originReplica: ReplicaId, concurrent: Boolean): Unit = {
    replicationContext._currentThread = OptionVal.Some(Thread.currentThread())
    replicationContext._recoveryRunning = recoveryRunning
    replicationContext._concurrent = concurrent
    replicationContext._origin = OptionVal.Some(originReplica)
  }

  def clearContext(): Unit = {
    replicationContext._currentThread = OptionVal.None
    replicationContext._recoveryRunning = false
    replicationContext._concurrent = false
    replicationContext._origin = OptionVal.None
  }

}
