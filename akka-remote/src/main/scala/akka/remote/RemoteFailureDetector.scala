/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.actor._
import Actor._
import akka.routing._
import akka.dispatch.PinnedDispatcher
import akka.event.EventHandler
import akka.util.{ ListenerManagement, Duration }
import scala.collection.immutable.Map
import scala.collection.mutable
import scala.annotation.tailrec
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import System.{ currentTimeMillis ⇒ newTimestamp }

/**
 * Base class for remote failure detection management.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
abstract class RemoteFailureDetectorBase(remote: Remote, initialConnections: Map[InetSocketAddress, ActorRef])
  extends FailureDetector
  with NetworkEventStream.Listener {

  type T <: AnyRef

  protected case class State(
    version: Long,
    connections: Map[InetSocketAddress, ActorRef],
    meta: T = null.asInstanceOf[T])
    extends VersionedIterable[ActorRef] {
    def iterable: Iterable[ActorRef] = connections.values
  }

  protected val state: AtomicReference[State] = new AtomicReference[State](newState())

  // register all initial connections - e.g listen to events from them
  initialConnections.keys foreach (remote.eventStream.register(this, _))

  /**
   * State factory. To be defined by subclass that wants to add extra info in the 'meta: T' field.
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
    EventHandler.debug(this, "RemoteFailureDetector failover from [%s] to [%s]".format(from, to))

    val oldState = state.get
    var changed = false

    val newMap = oldState.connections map {
      case (`from`, actorRef) ⇒
        changed = true
        //actorRef.stop()
        (to, newConnection(actorRef.address, to))
      case other ⇒ other
    }

    if (changed) {
      //there was a state change, so we are now going to update the state.
      val newState = oldState copy (version = oldState.version + 1, connections = newMap)

      //if we are not able to update, the state, we are going to try again.
      if (!state.compareAndSet(oldState, newState)) {
        failOver(from, to) // recur
      }
    }
  }

  @tailrec
  final def remove(faultyConnection: ActorRef) {

    val oldState = state.get()
    var changed = false

    var faultyAddress: InetSocketAddress = null
    var newConnections = Map.empty[InetSocketAddress, ActorRef]

    oldState.connections.keys foreach { address ⇒
      val actorRef: ActorRef = oldState.connections.get(address).get
      if (actorRef ne faultyConnection) {
        newConnections = newConnections + ((address, actorRef))
      } else {
        faultyAddress = address
        changed = true
      }
    }

    if (changed) {
      //one or more occurrances of the actorRef were removed, so we need to update the state.
      val newState = oldState copy (version = oldState.version + 1, connections = newConnections)

      //if we are not able to update the state, we just try again.
      if (!state.compareAndSet(oldState, newState)) {
        remove(faultyConnection) // recur
      } else {
        EventHandler.debug(this, "Removing connection [%s]".format(faultyAddress))
        remote.eventStream.unregister(this, faultyAddress) // unregister the connections - e.g stop listen to events from it
      }
    }
  }

  @tailrec
  final def putIfAbsent(address: InetSocketAddress, newConnectionFactory: () ⇒ ActorRef): ActorRef = {

    val oldState = state.get()
    val oldConnections = oldState.connections

    oldConnections.get(address) match {
      case Some(connection) ⇒ connection // we already had the connection, return it
      case None ⇒ // we need to create it
        val newConnection = newConnectionFactory()
        val newConnections = oldConnections + (address -> newConnection)

        //one or more occurrances of the actorRef were removed, so we need to update the state.
        val newState = oldState copy (version = oldState.version + 1, connections = newConnections)

        //if we are not able to update the state, we just try again.
        if (!state.compareAndSet(oldState, newState)) {
          // we failed, need compensating action
          newConnection.stop() // stop the new connection actor and try again
          putIfAbsent(address, newConnectionFactory) // recur
        } else {
          // we succeeded
          EventHandler.debug(this, "Adding connection [%s]".format(address))
          remote.eventStream.register(this, address) // register the connection - e.g listen to events from it
          newConnection // return new connection actor
        }
    }
  }

  private[remote] def newConnection(actorAddress: String, inetSocketAddress: InetSocketAddress) = {
    RemoteActorRef(remote.app, remote.server, inetSocketAddress, actorAddress, None)
  }
}

/**
 * Simple failure detector that removes the failing connection permanently on first error.
 */
class RemoveConnectionOnFirstFailureRemoteFailureDetector(_remote: Remote,
                                                          initialConnections: Map[InetSocketAddress, ActorRef] = Map.empty[InetSocketAddress, ActorRef])
  extends RemoteFailureDetectorBase(_remote, initialConnections) {

  protected def newState() = State(Long.MinValue, initialConnections)

  def isAvailable(connectionAddress: InetSocketAddress): Boolean = connections.get(connectionAddress).isDefined

  def recordSuccess(connectionAddress: InetSocketAddress, timestamp: Long) {}

  def recordFailure(connectionAddress: InetSocketAddress, timestamp: Long) {}

  def notify(event: RemoteLifeCycleEvent) = event match {
    case RemoteClientWriteFailed(request, cause, client, connectionAddress) ⇒
      removeConnection(connectionAddress)

    case RemoteClientError(cause, client, connectionAddress) ⇒
      removeConnection(connectionAddress)

    case RemoteClientDisconnected(client, connectionAddress) ⇒
      removeConnection(connectionAddress)

    case RemoteClientShutdown(client, connectionAddress) ⇒
      removeConnection(connectionAddress)

    case _ ⇒ {}
  }

  private def removeConnection(connectionAddress: InetSocketAddress) =
    connections.get(connectionAddress) foreach { conn ⇒ remove(conn) }
}

/**
 * Failure detector that bans the failing connection for 'timeToBan: Duration' and will try to use the connection
 * again after the ban period have expired.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class BannagePeriodFailureDetector(_remote: Remote,
                                   initialConnections: Map[InetSocketAddress, ActorRef] = Map.empty[InetSocketAddress, ActorRef],
                                   timeToBan: Duration)
  extends RemoteFailureDetectorBase(_remote, initialConnections) {

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
  // NetworkEventStream.Listener callback
  // ===================================================================================

  def notify(event: RemoteLifeCycleEvent) = event match {
    case RemoteClientStarted(client, connectionAddress) ⇒
      recordSuccess(connectionAddress, newTimestamp)

    case RemoteClientConnected(client, connectionAddress) ⇒
      recordSuccess(connectionAddress, newTimestamp)

    case RemoteClientWriteFailed(request, cause, client, connectionAddress) ⇒
      recordFailure(connectionAddress, newTimestamp)

    case RemoteClientError(cause, client, connectionAddress) ⇒
      recordFailure(connectionAddress, newTimestamp)

    case RemoteClientDisconnected(client, connectionAddress) ⇒
      recordFailure(connectionAddress, newTimestamp)

    case RemoteClientShutdown(client, connectionAddress) ⇒
      recordFailure(connectionAddress, newTimestamp)

    case _ ⇒ {}
  }
}

/**
 * Failure detector that uses the Circuit Breaker pattern to detect and recover from failing connections.
 *
 * class CircuitBreakerNetworkEventStream.Listener(initialConnections: Map[InetSocketAddress, ActorRef])
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
 * // FIXME implement CircuitBreakerNetworkEventStream.Listener
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