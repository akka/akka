/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.actor.{ Actor, ActorRef, Props }
import Actor._
import akka.cluster._
import akka.routing._
import akka.event.EventHandler
import akka.dispatch.{ Dispatchers, Future, PinnedDispatcher }
import akka.util.{ ListenerManagement, Duration }

import scala.collection.immutable.Map
import scala.collection.mutable
import scala.annotation.tailrec

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import System.{ currentTimeMillis ⇒ newTimestamp }

/**
 * Holds error event channel Actor instance and provides API for channel listener management.
 */
object RemoteFailureDetector {

  private sealed trait RemoteFailureDetectorChannelEvent

  private case class Register(listener: RemoteFailureListener, connectionAddress: InetSocketAddress)
    extends RemoteFailureDetectorChannelEvent

  private case class Unregister(listener: RemoteFailureListener, connectionAddress: InetSocketAddress)
    extends RemoteFailureDetectorChannelEvent

  private[akka] val channel = actorOf(Props(new Channel).copy(dispatcher = new PinnedDispatcher(), localOnly = true))

  def register(listener: RemoteFailureListener, connectionAddress: InetSocketAddress) =
    channel ! Register(listener, connectionAddress)

  def unregister(listener: RemoteFailureListener, connectionAddress: InetSocketAddress) =
    channel ! Unregister(listener, connectionAddress)

  private class Channel extends Actor {

    val listeners = new mutable.HashMap[InetSocketAddress, mutable.Set[RemoteFailureListener]]() {
      override def default(k: InetSocketAddress) = mutable.Set.empty[RemoteFailureListener]
    }

    def receive = {
      case event: RemoteClientLifeCycleEvent ⇒
        listeners(event.remoteAddress) foreach (_ notify event)

      case event: RemoteServerLifeCycleEvent ⇒ // FIXME handle RemoteServerLifeCycleEvent

      case Register(listener, connectionAddress) ⇒
        listeners(connectionAddress) += listener

      case Unregister(listener, connectionAddress) ⇒
        listeners(connectionAddress) -= listener

      case _ ⇒ //ignore other
    }
  }
}

/**
 * Base class for remote failure detection management.
 */
abstract class RemoteFailureDetectorBase(initialConnections: Map[InetSocketAddress, ActorRef])
  extends FailureDetector
  with RemoteFailureListener {

  import ClusterActorRef._

  type T <: AnyRef

  protected case class State(
    version: Long,
    connections: Map[InetSocketAddress, ActorRef],
    meta: T = null.asInstanceOf[T])
    extends VersionedIterable[ActorRef] {
    def iterable: Iterable[ActorRef] = connections.values
  }

  protected val state: AtomicReference[State] = {
    val ref = new AtomicReference[State]
    ref set newState()
    ref
  }

  /**
   * State factory. To be defined by subclass that wants to add extra info in the 'meta: Option[T]' field.
   */
  protected def newState(): State

  /**
   * Returns true if the 'connection' is considered available.
   *
   * To be implemented by subclass.
   */
  def isAvailable(connectionAddress: InetSocketAddress): Boolean

  /**
   * Records a successful connection.
   *
   * To be implemented by subclass.
   */
  def recordSuccess(connectionAddress: InetSocketAddress, timestamp: Long)

  /**
   * Records a failed connection.
   *
   * To be implemented by subclass.
   */
  def recordFailure(connectionAddress: InetSocketAddress, timestamp: Long)

  def version: Long = state.get.version

  def versionedIterable = state.get

  def size: Int = state.get.connections.size

  def connections: Map[InetSocketAddress, ActorRef] = state.get.connections

  def stopAll() {
    state.get.iterable foreach (_.stop()) // shut down all remote connections
  }

  @tailrec
  final def failOver(from: InetSocketAddress, to: InetSocketAddress) {
    EventHandler.debug(this, "ClusterActorRef failover from [%s] to [%s]".format(from, to))

    val oldState = state.get
    var changed = false

    val newMap = oldState.connections map {
      case (`from`, actorRef) ⇒
        changed = true
        //actorRef.stop()
        (to, createRemoteActorRef(actorRef.address, to))
      case other ⇒ other
    }

    if (changed) {
      //there was a state change, so we are now going to update the state.
      val newState = oldState copy (version = oldState.version + 1, connections = newMap)

      //if we are not able to update, the state, we are going to try again.
      if (!state.compareAndSet(oldState, newState)) failOver(from, to)
    }
  }

  @tailrec
  final def remove(faultyConnection: ActorRef) {
    EventHandler.debug(this, "ClusterActorRef remove [%s]".format(faultyConnection.uuid))

    val oldState = state.get()
    var changed = false

    //remote the faultyConnection from the clustered-connections.
    var newConnections = Map.empty[InetSocketAddress, ActorRef]
    oldState.connections.keys foreach { address ⇒
      val actorRef: ActorRef = oldState.connections.get(address).get
      if (actorRef ne faultyConnection) {
        newConnections = newConnections + ((address, actorRef))
      } else {
        changed = true
      }
    }

    if (changed) {
      //one or more occurrances of the actorRef were removed, so we need to update the state.
      val newState = oldState copy (version = oldState.version + 1, connections = newConnections)

      //if we are not able to update the state, we just try again.
      if (!state.compareAndSet(oldState, newState)) remove(faultyConnection)
    }
  }
}

/**
 * Simple failure detector that removes the failing connection permanently on first error.
 */
class RemoveConnectionOnFirstFailureRemoteFailureDetector(
  initialConnections: Map[InetSocketAddress, ActorRef])
  extends RemoteFailureDetectorBase(initialConnections) {

  protected def newState() = State(Long.MinValue, initialConnections)

  def isAvailable(connectionAddress: InetSocketAddress): Boolean = connections.get(connectionAddress).isDefined

  def recordSuccess(connectionAddress: InetSocketAddress, timestamp: Long) {}

  def recordFailure(connectionAddress: InetSocketAddress, timestamp: Long) {}

  override def remoteClientWriteFailed(
    request: AnyRef, cause: Throwable, client: RemoteClientModule, connectionAddress: InetSocketAddress) {
    removeConnection(connectionAddress)
  }

  override def remoteClientError(cause: Throwable, client: RemoteClientModule, connectionAddress: InetSocketAddress) {
    removeConnection(connectionAddress)
  }

  override def remoteClientDisconnected(client: RemoteClientModule, connectionAddress: InetSocketAddress) {
    removeConnection(connectionAddress)
  }

  override def remoteClientShutdown(client: RemoteClientModule, connectionAddress: InetSocketAddress) {
    removeConnection(connectionAddress)
  }

  private def removeConnection(connectionAddress: InetSocketAddress) =
    connections.get(connectionAddress) foreach { conn ⇒ remove(conn) }
}

/**
 * Failure detector that bans the failing connection for 'timeToBan: Duration' and will try to use the connection
 * again after the ban period have expired.
 */
class BannagePeriodFailureDetector(
  initialConnections: Map[InetSocketAddress, ActorRef],
  timeToBan: Duration)
  extends RemoteFailureDetectorBase(initialConnections) {

  // FIXME considering adding a Scheduler event to notify the BannagePeriodFailureDetector unban the banned connection after the timeToBan have exprired

  type T = Map[InetSocketAddress, BannedConnection]

  case class BannedConnection(bannedSince: Long, connection: ActorRef)

  val timeToBanInMillis = timeToBan.toMillis

  protected def newState() =
    State(Long.MinValue, initialConnections, Map.empty[InetSocketAddress, BannedConnection])

  private def removeConnection(connectionAddress: InetSocketAddress) =
    connections.get(connectionAddress) foreach { conn ⇒ remove(conn) }

  // ===================================================================================
  // FailureDetector callbacks
  // ===================================================================================

  def isAvailable(connectionAddress: InetSocketAddress): Boolean = connections.get(connectionAddress).isDefined

  @tailrec
  final def recordSuccess(connectionAddress: InetSocketAddress, timestamp: Long) {
    val oldState = state.get
    val bannedConnection = oldState.meta.get(connectionAddress)

    if (bannedConnection.isDefined) {
      val BannedConnection(bannedSince, connection) = bannedConnection.get
      val currentlyBannedFor = newTimestamp - bannedSince

      if (currentlyBannedFor > timeToBanInMillis) {
        // ban time has expired - add connection to available connections
        val newConnections = oldState.connections + (connectionAddress -> connection)
        val newBannedConnections = oldState.meta - connectionAddress

        val newState = oldState copy (version = oldState.version + 1,
          connections = newConnections,
          meta = newBannedConnections)

        if (!state.compareAndSet(oldState, newState)) recordSuccess(connectionAddress, timestamp)
      }
    }
  }

  @tailrec
  final def recordFailure(connectionAddress: InetSocketAddress, timestamp: Long) {
    val oldState = state.get
    val connection = oldState.connections.get(connectionAddress)

    if (connection.isDefined) {
      val newConnections = oldState.connections - connectionAddress
      val bannedConnection = BannedConnection(timestamp, connection.get)
      val newBannedConnections = oldState.meta + (connectionAddress -> bannedConnection)

      val newState = oldState copy (version = oldState.version + 1,
        connections = newConnections,
        meta = newBannedConnections)

      if (!state.compareAndSet(oldState, newState)) recordFailure(connectionAddress, timestamp)
    }
  }

  // ===================================================================================
  // RemoteFailureListener callbacks
  // ===================================================================================

  override def remoteClientStarted(client: RemoteClientModule, connectionAddress: InetSocketAddress) {
    recordSuccess(connectionAddress, newTimestamp)
  }

  override def remoteClientConnected(client: RemoteClientModule, connectionAddress: InetSocketAddress) {
    recordSuccess(connectionAddress, newTimestamp)
  }

  override def remoteClientWriteFailed(
    request: AnyRef, cause: Throwable, client: RemoteClientModule, connectionAddress: InetSocketAddress) {
    recordFailure(connectionAddress, newTimestamp)
  }

  override def remoteClientError(cause: Throwable, client: RemoteClientModule, connectionAddress: InetSocketAddress) {
    recordFailure(connectionAddress, newTimestamp)
  }

  override def remoteClientDisconnected(client: RemoteClientModule, connectionAddress: InetSocketAddress) {
    recordFailure(connectionAddress, newTimestamp)
  }

  override def remoteClientShutdown(client: RemoteClientModule, connectionAddress: InetSocketAddress) {
    recordFailure(connectionAddress, newTimestamp)
  }
}

/**
 * Failure detector that uses the Circuit Breaker pattern to detect and recover from failing connections.
 *
 * class CircuitBreakerRemoteFailureListener(initialConnections: Map[InetSocketAddress, ActorRef])
 * extends RemoteFailureDetectorBase(initialConnections) {
 *
 * def newState() = State(Long.MinValue, initialConnections, None)
 *
 * def isAvailable(connectionAddress: InetSocketAddress): Boolean = connections.get(connectionAddress).isDefined
 *
 * def recordSuccess(connectionAddress: InetSocketAddress, timestamp: Long) {}
 *
 * def recordFailure(connectionAddress: InetSocketAddress, timestamp: Long) {}
 *
 * // FIXME implement CircuitBreakerRemoteFailureListener
 * }
 */

/**
 * Base trait for remote failure event listener.
 */
trait RemoteFailureListener {

  final private[akka] def notify(event: RemoteLifeCycleEvent) = event match {
    case RemoteClientStarted(client, connectionAddress) ⇒
      remoteClientStarted(client, connectionAddress)

    case RemoteClientConnected(client, connectionAddress) ⇒
      remoteClientConnected(client, connectionAddress)

    case RemoteClientWriteFailed(request, cause, client, connectionAddress) ⇒
      remoteClientWriteFailed(request, cause, client, connectionAddress)

    case RemoteClientError(cause, client, connectionAddress) ⇒
      remoteClientError(cause, client, connectionAddress)

    case RemoteClientDisconnected(client, connectionAddress) ⇒
      remoteClientDisconnected(client, connectionAddress)

    case RemoteClientShutdown(client, connectionAddress) ⇒
      remoteClientShutdown(client, connectionAddress)

    case RemoteServerWriteFailed(request, cause, server, clientAddress) ⇒
      remoteServerWriteFailed(request, cause, server, clientAddress)

    case RemoteServerError(cause, server) ⇒
      remoteServerError(cause, server)

    case RemoteServerShutdown(server) ⇒
      remoteServerShutdown(server)
  }

  def remoteClientStarted(client: RemoteClientModule, connectionAddress: InetSocketAddress) {}

  def remoteClientConnected(client: RemoteClientModule, connectionAddress: InetSocketAddress) {}

  def remoteClientWriteFailed(
    request: AnyRef, cause: Throwable, client: RemoteClientModule, connectionAddress: InetSocketAddress) {}

  def remoteClientError(cause: Throwable, client: RemoteClientModule, connectionAddress: InetSocketAddress) {}

  def remoteClientDisconnected(client: RemoteClientModule, connectionAddress: InetSocketAddress) {}

  def remoteClientShutdown(client: RemoteClientModule, connectionAddress: InetSocketAddress) {}

  def remoteServerWriteFailed(
    request: AnyRef, cause: Throwable, server: RemoteServerModule, clientAddress: Option[InetSocketAddress]) {}

  def remoteServerError(cause: Throwable, server: RemoteServerModule) {}

  def remoteServerShutdown(server: RemoteServerModule) {}
}
