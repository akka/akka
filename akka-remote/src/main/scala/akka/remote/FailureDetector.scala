/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.AkkaException
import akka.actor._
import akka.event.EventHandler
import akka.config.ConfigurationException
import akka.actor.UntypedChannel._
import akka.dispatch.{ Future, Futures }
import akka.util.ReflectiveAccess
import akka.util.Duration

import java.net.InetSocketAddress
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.{ AtomicReference, AtomicInteger }

import scala.collection.immutable.Map
import scala.collection.mutable
import scala.annotation.tailrec

/**
 * The failure detector uses different heuristics (depending on implementation) to try to detect and manage
 * failed connections.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait FailureDetector extends NetworkEventStream.Listener {

  def newTimestamp: Long = System.currentTimeMillis

  /**
   * Returns true if the 'connection' is considered available.
   */
  def isAvailable(connection: InetSocketAddress): Boolean

  /**
   * Records a successful connection.
   */
  def recordSuccess(connection: InetSocketAddress, timestamp: Long)

  /**
   * Records a failed connection.
   */
  def recordFailure(connection: InetSocketAddress, timestamp: Long)
}

/**
 * Misc helper and factory methods for failure detection.
 */
object FailureDetector {

  def createCustomFailureDetector(implClass: String): FailureDetector = {

    ReflectiveAccess.createInstance(
      implClass,
      Array[Class[_]](),
      Array[AnyRef]()) match {
        case Right(actor) ⇒ actor
        case Left(exception) ⇒
          val cause = exception match {
            case i: InvocationTargetException ⇒ i.getTargetException
            case _                            ⇒ exception
          }
          throw new ConfigurationException(
            "Could not instantiate custom FailureDetector of [" +
              implClass + "] due to: " +
              cause, cause)
      }
  }
}

/**
 * No-op failure detector. Does not do anything.
 */
class NoOpFailureDetector extends FailureDetector {

  def isAvailable(connection: InetSocketAddress): Boolean = true

  def recordSuccess(connection: InetSocketAddress, timestamp: Long) {}

  def recordFailure(connection: InetSocketAddress, timestamp: Long) {}

  def notify(event: RemoteLifeCycleEvent) {}
}

/**
 * Simple failure detector that removes the failing connection permanently on first error.
 */
class RemoveConnectionOnFirstFailureFailureDetector extends FailureDetector {

  protected case class State(version: Long, banned: Set[InetSocketAddress])

  protected val state: AtomicReference[State] = new AtomicReference[State](newState())

  protected def newState() = State(Long.MinValue, Set.empty[InetSocketAddress])

  def isAvailable(connectionAddress: InetSocketAddress): Boolean = state.get.banned.contains(connectionAddress)

  final def recordSuccess(connectionAddress: InetSocketAddress, timestamp: Long) {}

  @tailrec
  final def recordFailure(connectionAddress: InetSocketAddress, timestamp: Long) {
    val oldState = state.get
    if (!oldState.banned.contains(connectionAddress)) {
      val newBannedConnections = oldState.banned + connectionAddress
      val newState = oldState copy (version = oldState.version + 1, banned = newBannedConnections)
      if (!state.compareAndSet(oldState, newState)) recordFailure(connectionAddress, timestamp)
    }
  }

  // NetworkEventStream.Listener callback
  def notify(event: RemoteLifeCycleEvent) = event match {
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
 * Failure detector that bans the failing connection for 'timeToBan: Duration' and will try to use the connection
 * again after the ban period have expired.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class BannagePeriodFailureDetector(timeToBan: Duration) extends FailureDetector with NetworkEventStream.Listener {

  // FIXME considering adding a Scheduler event to notify the BannagePeriodFailureDetector unban the banned connection after the timeToBan have exprired

  protected case class State(version: Long, banned: Map[InetSocketAddress, BannedConnection])

  protected val state: AtomicReference[State] = new AtomicReference[State](newState())

  case class BannedConnection(bannedSince: Long, address: InetSocketAddress)

  val timeToBanInMillis = timeToBan.toMillis

  protected def newState() = State(Long.MinValue, Map.empty[InetSocketAddress, BannedConnection])

  private def bannedConnections = state.get.banned

  def isAvailable(connectionAddress: InetSocketAddress): Boolean = bannedConnections.get(connectionAddress).isEmpty

  @tailrec
  final def recordSuccess(connectionAddress: InetSocketAddress, timestamp: Long) {
    val oldState = state.get
    val bannedConnection = oldState.banned.get(connectionAddress)

    if (bannedConnection.isDefined) { // is it banned or not?
      val BannedConnection(bannedSince, banned) = bannedConnection.get
      val currentlyBannedFor = newTimestamp - bannedSince

      if (currentlyBannedFor > timeToBanInMillis) {
        val newBannedConnections = oldState.banned - connectionAddress

        val newState = oldState copy (version = oldState.version + 1, banned = newBannedConnections)

        if (!state.compareAndSet(oldState, newState)) recordSuccess(connectionAddress, timestamp)
      }
    }
  }

  @tailrec
  final def recordFailure(connectionAddress: InetSocketAddress, timestamp: Long) {
    val oldState = state.get
    val connection = oldState.banned.get(connectionAddress)

    if (connection.isEmpty) { // is it already banned or not?
      val bannedConnection = BannedConnection(timestamp, connectionAddress)
      val newBannedConnections = oldState.banned + (connectionAddress -> bannedConnection)

      val newState = oldState copy (version = oldState.version + 1, banned = newBannedConnections)

      if (!state.compareAndSet(oldState, newState)) recordFailure(connectionAddress, timestamp)
    }
  }

  // NetworkEventStream.Listener callback
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
 * extends RemoteConnectionManager(initialConnections) {
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
