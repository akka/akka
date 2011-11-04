/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.{ AkkaException, AkkaApplication }
import akka.actor._
import akka.actor.Actor._
import akka.actor.Status._
import akka.routing._
import akka.dispatch._
import akka.util.duration._
import akka.config.ConfigurationException
import akka.event.{ DeathWatch, EventHandler }
import akka.serialization.{ Serialization, Serializer, Compression }
import akka.serialization.Compression.LZF
import akka.remote.RemoteProtocol._
import akka.remote.RemoteProtocol.RemoteSystemDaemonMessageType._

import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

import com.google.protobuf.ByteString
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Remote ActorRefProvider. Starts up actor on remote node and creates a RemoteActorRef representing it.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RemoteActorRefProvider(val app: AkkaApplication) extends ActorRefProvider {

  import java.util.concurrent.ConcurrentHashMap
  import akka.dispatch.Promise

  val local = new LocalActorRefProvider(app)
  val remote = new Remote(app)

  private val actors = new ConcurrentHashMap[String, AnyRef]

  private val remoteDaemonConnectionManager = new RemoteConnectionManager(app, remote)

  private[akka] def theOneWhoWalksTheBubblesOfSpaceTime: ActorRef = local.theOneWhoWalksTheBubblesOfSpaceTime
  private[akka] def terminationFuture = local.terminationFuture

  private[akka] def deployer: Deployer = local.deployer

  def defaultDispatcher = app.dispatcher
  def defaultTimeout = app.AkkaConfig.ActorTimeout

  def actorOf(props: Props, supervisor: ActorRef, address: String, systemService: Boolean): ActorRef =
    if (systemService) local.actorOf(props, supervisor, address, systemService)
    else {
      val newFuture = Promise[ActorRef](5000)(defaultDispatcher) // FIXME is this proper timeout?

      actors.putIfAbsent(address, newFuture) match { // we won the race -- create the actor and resolve the future
        case null ⇒
          val actor: ActorRef = try {
            deployer.lookupDeploymentFor(address) match {
              case Some(DeploymentConfig.Deploy(_, _, routerType, nrOfInstances, failureDetectorType, DeploymentConfig.RemoteScope(remoteAddresses))) ⇒

                // FIXME move to AccrualFailureDetector as soon as we have the Gossiper up and running and remove the option to select impl in the akka.conf file since we only have one
                // val failureDetector = DeploymentConfig.failureDetectorTypeFor(failureDetectorType) match {
                //   case FailureDetectorType.NoOp                           ⇒ new NoOpFailureDetector
                //   case FailureDetectorType.RemoveConnectionOnFirstFailure ⇒ new RemoveConnectionOnFirstFailureFailureDetector
                //   case FailureDetectorType.BannagePeriod(timeToBan)       ⇒ new BannagePeriodFailureDetector(timeToBan)
                //   case FailureDetectorType.Custom(implClass)              ⇒ FailureDetector.createCustomFailureDetector(implClass)
                // }

                def isReplicaNode: Boolean = remoteAddresses exists { some ⇒ some.port == app.port && some.hostname == app.hostname }

                //app.eventHandler.debug(this, "%s: Deploy Remote Actor with address [%s] connected to [%s]: isReplica(%s)".format(app.defaultAddress, address, remoteAddresses.mkString, isReplicaNode))

                if (isReplicaNode) {
                  // we are on one of the replica node for this remote actor
                  local.actorOf(props, supervisor, address, true) //FIXME systemService = true here to bypass Deploy, should be fixed when create-or-get is replaced by get-or-create
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
                      () ⇒ new ScatterGatherFirstCompletedRouter()(defaultDispatcher, defaultTimeout)

                    case RouterType.LeastCPU          ⇒ sys.error("Router LeastCPU not supported yet")
                    case RouterType.LeastRAM          ⇒ sys.error("Router LeastRAM not supported yet")
                    case RouterType.LeastMessages     ⇒ sys.error("Router LeastMessages not supported yet")
                    case RouterType.Custom(implClass) ⇒ () ⇒ Routing.createCustomRouter(implClass)
                  }

                  val connections = (Map.empty[InetSocketAddress, ActorRef] /: remoteAddresses) { (conns, a) ⇒
                    val inetAddr = new InetSocketAddress(a.hostname, a.port)
                    conns + (inetAddr -> RemoteActorRef(remote.server, inetAddr, address, None))
                  }

                  val connectionManager = new RemoteConnectionManager(app, remote, connections)

                  connections.keys foreach { useActorOnNode(_, address, props.creator) }

                  actorOf(RoutedProps(routerFactory = routerFactory, connectionManager = connectionManager), supervisor, address)
                }

              case deploy ⇒ local.actorOf(props, supervisor, address, systemService)
            }
          } catch {
            case e: Exception ⇒
              newFuture completeWithException e // so the other threads gets notified of error
              throw e
          }

          // actor foreach app.registry.register // only for ActorRegistry backward compat, will be removed later

          newFuture completeWithResult actor
          actors.replace(address, newFuture, actor)
          actor
        case actor: ActorRef   ⇒ actor
        case future: Future[_] ⇒ future.get.asInstanceOf[ActorRef]
      }
    }

  /**
   * Copied from LocalActorRefProvider...
   */
  // FIXME: implement supervision
  def actorOf(props: RoutedProps, supervisor: ActorRef, address: String): ActorRef = {
    if (props.connectionManager.isEmpty) throw new ConfigurationException("RoutedProps used for creating actor [" + address + "] has zero connections configured; can't create a router")
    new RoutedActorRef(app, props, address)
  }

  def actorFor(address: String): Option[ActorRef] = actors.get(address) match {
    case null              ⇒ local.actorFor(address)
    case actor: ActorRef   ⇒ Some(actor)
    case future: Future[_] ⇒ Some(future.get.asInstanceOf[ActorRef])
  }

  val optimizeLocal = new AtomicBoolean(true)
  def optimizeLocalScoped_?() = optimizeLocal.get

  /**
   * Returns true if the actor was in the provider's cache and evicted successfully, else false.
   */
  private[akka] def evict(address: String): Boolean = actors.remove(address) ne null
  private[akka] def serialize(actor: ActorRef): SerializedActorRef = actor match {
    case r: RemoteActorRef ⇒ new SerializedActorRef(actor.address, r.remoteAddress)
    case other             ⇒ local.serialize(actor)
  }

  private[akka] def deserialize(actor: SerializedActorRef): Option[ActorRef] = {
    if (optimizeLocalScoped_? && (actor.hostname == app.hostname || actor.hostname == app.defaultAddress.getHostName) && actor.port == app.port) {
      local.actorFor(actor.address)
    } else {
      val remoteInetSocketAddress = new InetSocketAddress(actor.hostname, actor.port) //FIXME Drop the InetSocketAddresses and use RemoteAddress
      app.eventHandler.debug(this, "%s: Creating RemoteActorRef with address [%s] connected to [%s]".format(app.defaultAddress, actor.address, remoteInetSocketAddress))
      Some(RemoteActorRef(remote.server, remoteInetSocketAddress, actor.address, None)) //Should it be None here
    }
  }

  /**
   * Using (checking out) actor on a specific node.
   */
  def useActorOnNode(remoteAddress: InetSocketAddress, actorAddress: String, actorFactory: () ⇒ Actor) {
    app.eventHandler.debug(this, "[%s] Instantiating Actor [%s] on node [%s]".format(app.defaultAddress, actorAddress, remoteAddress))

    val actorFactoryBytes =
      app.serialization.serialize(actorFactory) match {
        case Left(error)  ⇒ throw error
        case Right(bytes) ⇒ if (remote.shouldCompressData) LZF.compress(bytes) else bytes
      }

    val command = RemoteSystemDaemonMessageProtocol.newBuilder
      .setMessageType(USE)
      .setActorAddress(actorAddress)
      .setPayload(ByteString.copyFrom(actorFactoryBytes))
      .build()

    val connectionFactory = () ⇒ deserialize(new SerializedActorRef(remote.remoteDaemonServiceName, remoteAddress)).get

    // try to get the connection for the remote address, if not already there then create it
    val connection = remoteDaemonConnectionManager.putIfAbsent(remoteAddress, connectionFactory)

    sendCommandToRemoteNode(connection, command, withACK = true) // ensure we get an ACK on the USE command
  }

  private def sendCommandToRemoteNode(connection: ActorRef, command: RemoteSystemDaemonMessageProtocol, withACK: Boolean) {
    if (withACK) {
      try {
        val f = connection ? (command, remote.remoteSystemDaemonAckTimeout)
        (try f.await.value catch { case _: FutureTimeoutException ⇒ None }) match {
          case Some(Right(receiver)) ⇒
            app.eventHandler.debug(this, "Remote system command sent to [%s] successfully received".format(receiver))

          case Some(Left(cause)) ⇒
            app.eventHandler.error(cause, this, cause.toString)
            throw cause

          case None ⇒
            val error = new RemoteException("Remote system command to [%s] timed out".format(connection.address))
            app.eventHandler.error(error, this, error.toString)
            throw error
        }
      } catch {
        case e: Exception ⇒
          app.eventHandler.error(e, this, "Could not send remote system command to [%s] due to: %s".format(connection.address, e.toString))
          throw e
      }
    } else {
      connection ! command
    }
  }

  private[akka] def createDeathWatch(): DeathWatch = local.createDeathWatch() //FIXME Implement Remote DeathWatch

  private[akka] def ask(message: Any, recipient: ActorRef, within: Timeout): Future[Any] = local.ask(message, recipient, within)
}

/**
 * Remote ActorRef that is used when referencing the Actor on a different node than its "home" node.
 * This reference is network-aware (remembers its origin) and immutable.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
private[akka] case class RemoteActorRef private[akka] (
  remote: RemoteSupport,
  remoteAddress: InetSocketAddress,
  address: String,
  loader: Option[ClassLoader])
  extends ActorRef with ScalaActorRef {
  @volatile
  private var running: Boolean = true

  def isShutdown: Boolean = !running

  protected[akka] def sendSystemMessage(message: SystemMessage): Unit = unsupported

  def postMessageToMailbox(message: Any, sender: ActorRef): Unit = remote.send(message, Option(sender), remoteAddress, this, loader)

  def ?(message: Any)(implicit timeout: Timeout): Future[Any] = remote.app.provider.ask(message, this, timeout)

  def suspend(): Unit = ()

  def resume(): Unit = ()

  def stop() { //FIXME send the cause as well!
    synchronized {
      if (running) {
        running = false
        remote.send(new Terminate(), None, remoteAddress, this, loader)
      }
    }
  }

  @throws(classOf[java.io.ObjectStreamException])
  private def writeReplace(): AnyRef = remote.app.provider.serialize(this)

  def startsMonitoring(actorRef: ActorRef): ActorRef = unsupported //FIXME Implement

  def stopsMonitoring(actorRef: ActorRef): ActorRef = unsupported //FIXME Implement

  protected[akka] def restart(cause: Throwable): Unit = ()

  private def unsupported = throw new UnsupportedOperationException("Not supported for RemoteActorRef")
}
