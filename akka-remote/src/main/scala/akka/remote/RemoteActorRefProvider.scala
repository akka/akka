/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.actor._
import akka.routing._
import akka.actor.Actor._
import akka.actor.Status._
import akka.event.EventHandler
import akka.util.duration._
import akka.config.ConfigurationException
import akka.AkkaException
import RemoteProtocol._
import RemoteDaemonMessageType._
import akka.serialization.{ Serialization, Serializer, ActorSerialization, Compression }
import Compression.LZF

import java.net.InetSocketAddress

import com.google.protobuf.ByteString

/**
 * Remote ActorRefProvider. Starts up actor on remote node and creates a RemoteActorRef representing it.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RemoteActorRefProvider extends ActorRefProvider {

  import java.util.concurrent.ConcurrentHashMap
  import akka.dispatch.Promise

  private val actors = new ConcurrentHashMap[String, Promise[Option[ActorRef]]]

  private val remoteDaemonConnectionManager = new RemoteConnectionManager(failureDetector = new BannagePeriodFailureDetector(60 seconds)) // FIXME make timeout configurable

  def actorOf(props: Props, address: String): Option[ActorRef] = {
    Address.validate(address)

    val newFuture = Promise[Option[ActorRef]](5000) // FIXME is this proper timeout?
    val oldFuture = actors.putIfAbsent(address, newFuture)

    if (oldFuture eq null) { // we won the race -- create the actor and resolve the future
      val actor = try {
        Deployer.lookupDeploymentFor(address) match {
          case Some(DeploymentConfig.Deploy(_, _, routerType, nrOfInstances, failureDetectorType, DeploymentConfig.RemoteScope(remoteAddresses))) ⇒

            val failureDetector = DeploymentConfig.failureDetectorTypeFor(failureDetectorType) match {
              case FailureDetectorType.NoOp                           ⇒ new NoOpFailureDetector
              case FailureDetectorType.RemoveConnectionOnFirstFailure ⇒ new RemoveConnectionOnFirstFailureFailureDetector
              case FailureDetectorType.BannagePeriod(timeToBan)       ⇒ new BannagePeriodFailureDetector(timeToBan)
              case FailureDetectorType.Custom(implClass)              ⇒ FailureDetector.createCustomFailureDetector(implClass)
            }

            val thisHostname = Remote.address.getHostName
            val thisPort = Remote.address.getPort

            def isReplicaNode: Boolean = remoteAddresses exists { remoteAddress ⇒
              remoteAddress.hostname == thisHostname && remoteAddress.port == thisPort
            }

            if (isReplicaNode) {
              // we are on one of the replica node for this remote actor
              Some(new LocalActorRef(props, address, false)) // create a local actor
            } else {

              // we are on the single "reference" node uses the remote actors on the replica nodes
              val routerFactory: () ⇒ Router = DeploymentConfig.routerTypeFor(routerType) match {
                case RouterType.Direct ⇒
                  if (remoteAddresses.size != 1) throw new ConfigurationException(
                    "Actor [%s] configured with Direct router must have exactly 1 remote node configured. Found [%s]"
                      .format(address, remoteAddresses.mkString(", ")))
                  () ⇒ new DirectRouter

                case RouterType.Random ⇒
                  if (remoteAddresses.size < 1) throw new ConfigurationException(
                    "Actor [%s] configured with Random router must have at least 1 remote node configured. Found [%s]"
                      .format(address, remoteAddresses.mkString(", ")))
                  () ⇒ new RandomRouter

                case RouterType.RoundRobin ⇒
                  if (remoteAddresses.size < 1) throw new ConfigurationException(
                    "Actor [%s] configured with RoundRobin router must have at least 1 remote node configured. Found [%s]"
                      .format(address, remoteAddresses.mkString(", ")))
                  () ⇒ new RoundRobinRouter

                case RouterType.ScatterGather ⇒
                  if (remoteAddresses.size < 1) throw new ConfigurationException(
                    "Actor [%s] configured with ScatterGather router must have at least 1 remote node configured. Found [%s]"
                      .format(address, remoteAddresses.mkString(", ")))
                  () ⇒ new ScatterGatherFirstCompletedRouter

                case RouterType.LeastCPU          ⇒ sys.error("Router LeastCPU not supported yet")
                case RouterType.LeastRAM          ⇒ sys.error("Router LeastRAM not supported yet")
                case RouterType.LeastMessages     ⇒ sys.error("Router LeastMessages not supported yet")
                case RouterType.Custom(implClass) ⇒ () ⇒ Routing.createCustomRouter(implClass)
              }

              var connections = Map.empty[InetSocketAddress, ActorRef]
              remoteAddresses foreach { remoteAddress: DeploymentConfig.RemoteAddress ⇒
                val inetSocketAddress = new InetSocketAddress(remoteAddress.hostname, remoteAddress.port)
                connections += (inetSocketAddress -> RemoteActorRef(inetSocketAddress, address, Actor.TIMEOUT, None))
              }

              val connectionManager = new RemoteConnectionManager(connections, failureDetector)

              connections.keys foreach { useActorOnNode(_, address, props.creator) }

              Some(Routing.actorOf(RoutedProps(
                routerFactory = routerFactory,
                connectionManager = connectionManager)))
            }

          case deploy ⇒ None // non-remote actor
        }
      } catch {
        case e: Exception ⇒
          newFuture completeWithException e // so the other threads gets notified of error
          throw e
      }

      //      actor foreach Actor.registry.register // only for ActorRegistry backward compat, will be removed later

      newFuture completeWithResult actor
      actor

    } else { // we lost the race -- wait for future to complete
      oldFuture.await.resultOrException.getOrElse(None)
    }
  }

  def actorFor(address: String): Option[ActorRef] = actors.get(address) match {
    case null   ⇒ None
    case future ⇒ future.await.resultOrException.getOrElse(None)
  }

  /**
   * Returns true if the actor was in the provider's cache and evicted successfully, else false.
   */
  private[akka] def evict(address: String): Boolean = actors.remove(address) ne null

  /**
   * Using (checking out) actor on a specific node.
   */
  def useActorOnNode(remoteAddress: InetSocketAddress, actorAddress: String, actorFactory: () ⇒ Actor) {
    EventHandler.debug(this, "Instantiating Actor [%s] on node [%s]".format(actorAddress, remoteAddress))

    val actorFactoryBytes =
      Serialization.serialize(actorFactory) match {
        case Left(error) ⇒ throw error
        case Right(bytes) ⇒
          if (Remote.shouldCompressData) LZF.compress(bytes)
          else bytes
      }

    val command = RemoteDaemonMessageProtocol.newBuilder
      .setMessageType(USE)
      .setActorAddress(actorAddress)
      .setPayload(ByteString.copyFrom(actorFactoryBytes))
      .build()

    val connectionFactory =
      () ⇒ Remote.server.actorFor(
        Remote.remoteDaemonServiceName, remoteAddress.getHostName, remoteAddress.getPort)

    // try to get the connection for the remote address, if not already there then create it
    val connection = remoteDaemonConnectionManager.putIfAbsent(remoteAddress, connectionFactory)

    sendCommandToRemoteNode(connection, command, withACK = true) // ensure we get an ACK on the USE command
  }

  private def sendCommandToRemoteNode(
    connection: ActorRef,
    command: RemoteDaemonMessageProtocol,
    withACK: Boolean) {

    if (withACK) {
      try {
        (connection ? (command, Remote.remoteDaemonAckTimeout)).as[Status] match {
          case Some(Success(receiver)) ⇒
            EventHandler.debug(this, "Remote command sent to [%s] successfully received".format(receiver))

          case Some(Failure(cause)) ⇒
            EventHandler.error(cause, this, cause.toString)
            throw cause

          case None ⇒
            val error = new RemoteException("Remote command to [%s] timed out".format(connection.address))
            EventHandler.error(error, this, error.toString)
            throw error
        }
      } catch {
        case e: Exception ⇒
          EventHandler.error(e, this, "Could not send remote command to [%s] due to: %s".format(connection.address, e.toString))
          throw e
      }
    } else {
      connection ! command
    }
  }
}
