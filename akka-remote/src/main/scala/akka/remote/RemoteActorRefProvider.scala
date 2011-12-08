/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.AkkaException
import akka.actor._
import akka.actor.Actor._
import akka.actor.Status._
import akka.routing._
import akka.dispatch._
import akka.util.duration._
import akka.config.ConfigurationException
import akka.event.{ DeathWatch, Logging }
import akka.serialization.Compression.LZF
import akka.remote.RemoteProtocol._
import akka.remote.RemoteProtocol.RemoteSystemDaemonMessageType._
import com.google.protobuf.ByteString
import java.util.concurrent.atomic.AtomicBoolean
import akka.event.EventStream
import java.util.concurrent.ConcurrentHashMap
import akka.dispatch.Promise
import java.net.InetAddress
import akka.serialization.SerializationExtension
import akka.serialization.Serialization

/**
 * Remote ActorRefProvider. Starts up actor on remote node and creates a RemoteActorRef representing it.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RemoteActorRefProvider(
  val systemName: String,
  val settings: ActorSystem.Settings,
  val eventStream: EventStream,
  val scheduler: Scheduler,
  _deadLetters: InternalActorRef) extends ActorRefProvider {

  val log = Logging(eventStream, "RemoteActorRefProvider")

  val remoteSettings = new RemoteSettings(settings.config, systemName)

  def deathWatch = local.deathWatch
  def rootGuardian = local.rootGuardian
  def guardian = local.guardian
  def systemGuardian = local.systemGuardian
  def nodename = remoteSettings.NodeName
  def clustername = remoteSettings.ClusterName

  private val actors = new ConcurrentHashMap[String, AnyRef]

  val rootPath: ActorPath = RootActorPath(RemoteAddress(systemName, remoteSettings.serverSettings.Hostname, remoteSettings.serverSettings.Port))
  private val local = new LocalActorRefProvider(systemName, settings, eventStream, scheduler, _deadLetters, rootPath)
  private var serialization: Serialization = _
  private var remoteDaemonConnectionManager: RemoteConnectionManager = _

  private var _remote: Remote = _
  def remote = _remote

  def init(system: ActorSystemImpl) {
    local.init(system)
    serialization = SerializationExtension(system)
    _remote = new Remote(system, nodename, remoteSettings)
    remoteDaemonConnectionManager = new RemoteConnectionManager(system, remote)
    terminationFuture.onComplete(_ ⇒ remote.server.shutdown())
  }

  private[akka] def terminationFuture = local.terminationFuture

  private[akka] def deployer: Deployer = new RemoteDeployer(settings, eventStream, nodename)

  def dispatcher = local.dispatcher
  def defaultTimeout = settings.ActorTimeout

  def actorOf(system: ActorSystemImpl, props: Props, supervisor: InternalActorRef, name: String, systemService: Boolean): InternalActorRef =
    if (systemService) local.actorOf(system, props, supervisor, name, systemService)
    else {
      val path = supervisor.path / name
      val newFuture = Promise[ActorRef](system.settings.ActorTimeout)(dispatcher)

      actors.putIfAbsent(path.toString, newFuture) match { // we won the race -- create the actor and resolve the future
        case null ⇒
          val actor: InternalActorRef = try {
            deployer.lookupDeploymentFor(path.toString) match {
              case Some(DeploymentConfig.Deploy(_, _, routerType, nrOfInstances, RemoteDeploymentConfig.RemoteScope(remoteAddresses))) ⇒

                def isReplicaNode: Boolean = remoteAddresses exists { _ == remote.remoteAddress }

                //system.eventHandler.debug(this, "%s: Deploy Remote Actor with address [%s] connected to [%s]: isReplica(%s)".format(system.defaultAddress, address, remoteAddresses.mkString, isReplicaNode))

                if (isReplicaNode) {
                  // we are on one of the replica node for this remote actor
                  local.actorOf(system, props, supervisor, name, true) //FIXME systemService = true here to bypass Deploy, should be fixed when create-or-get is replaced by get-or-create (is this fixed now?)
                } else {

                  implicit val dispatcher = if (props.dispatcher == Props.defaultDispatcher) system.dispatcher else props.dispatcher
                  implicit val timeout = system.settings.ActorTimeout

                  // we are on the single "reference" node uses the remote actors on the replica nodes
                  val routerFactory: () ⇒ Router = DeploymentConfig.routerTypeFor(routerType) match {
                    case RouterType.Direct ⇒
                      if (remoteAddresses.size != 1) throw new ConfigurationException(
                        "Actor [%s] configured with Direct router must have exactly 1 remote node configured. Found [%s]"
                          .format(name, remoteAddresses.mkString(", ")))
                      () ⇒ new DirectRouter

                    case RouterType.Broadcast ⇒
                      if (remoteAddresses.size != 1) throw new ConfigurationException(
                        "Actor [%s] configured with Broadcast router must have exactly 1 remote node configured. Found [%s]"
                          .format(name, remoteAddresses.mkString(", ")))
                      () ⇒ new BroadcastRouter

                    case RouterType.Random ⇒
                      if (remoteAddresses.size < 1) throw new ConfigurationException(
                        "Actor [%s] configured with Random router must have at least 1 remote node configured. Found [%s]"
                          .format(name, remoteAddresses.mkString(", ")))
                      () ⇒ new RandomRouter

                    case RouterType.RoundRobin ⇒
                      if (remoteAddresses.size < 1) throw new ConfigurationException(
                        "Actor [%s] configured with RoundRobin router must have at least 1 remote node configured. Found [%s]"
                          .format(name, remoteAddresses.mkString(", ")))
                      () ⇒ new RoundRobinRouter

                    case RouterType.ScatterGather ⇒
                      if (remoteAddresses.size < 1) throw new ConfigurationException(
                        "Actor [%s] configured with ScatterGather router must have at least 1 remote node configured. Found [%s]"
                          .format(name, remoteAddresses.mkString(", ")))
                      () ⇒ new ScatterGatherFirstCompletedRouter()(dispatcher, defaultTimeout)

                    case RouterType.LeastCPU          ⇒ sys.error("Router LeastCPU not supported yet")
                    case RouterType.LeastRAM          ⇒ sys.error("Router LeastRAM not supported yet")
                    case RouterType.LeastMessages     ⇒ sys.error("Router LeastMessages not supported yet")
                    case RouterType.Custom(implClass) ⇒ () ⇒ Routing.createCustomRouter(implClass)
                  }

                  val connections = (Map.empty[RemoteAddress, ActorRef] /: remoteAddresses) { (conns, a) ⇒
                    conns + (a -> new RemoteActorRef(this, remote.server, path, None)) // FIXME RK correct path must be put in here
                  }

                  val connectionManager = new RemoteConnectionManager(system, remote, connections)

                  connections.keys foreach { useActorOnNode(system, _, path.toString, props.creator) }

                  actorOf(system, RoutedProps(routerFactory = routerFactory, connectionManager = connectionManager), supervisor, name)
                }

              case deploy ⇒ local.actorOf(system, props, supervisor, name, systemService)
            }
          } catch {
            case e: Exception ⇒
              newFuture completeWithException e // so the other threads gets notified of error
              throw e
          }

          // actor foreach system.registry.register // only for ActorRegistry backward compat, will be removed later

          newFuture completeWithResult actor
          actors.replace(path.toString, newFuture, actor)
          actor
        case actor: InternalActorRef ⇒ actor
        case future: Future[_]       ⇒ future.get.asInstanceOf[InternalActorRef]
      }
    }

  /**
   * Copied from LocalActorRefProvider...
   */
  // FIXME: implement supervision, ticket #1408
  def actorOf(system: ActorSystem, props: RoutedProps, supervisor: InternalActorRef, name: String): InternalActorRef = {
    if (props.connectionManager.isEmpty) throw new ConfigurationException("RoutedProps used for creating actor [" + name + "] has zero connections configured; can't create a router")
    new RoutedActorRef(system, props, supervisor, name)
  }

  def actorFor(path: ActorPath): InternalActorRef = path.root match {
    case `rootPath`                         ⇒ actorFor(rootGuardian, path.elements)
    case RootActorPath(_: RemoteAddress, _) ⇒ new RemoteActorRef(this, remote.server, path, None)
    case _                                  ⇒ local.actorFor(path)
  }

  def actorFor(ref: InternalActorRef, path: String): InternalActorRef = path match {
    case RemoteActorPath(address, elems) ⇒
      if (address == rootPath.address) actorFor(rootGuardian, elems)
      else new RemoteActorRef(this, remote.server, new RootActorPath(address) / elems, None)
    case _ ⇒ local.actorFor(ref, path)
  }

  def actorFor(ref: InternalActorRef, path: Iterable[String]): InternalActorRef = local.actorFor(ref, path)

  // TODO remove me
  val optimizeLocal = new AtomicBoolean(true)
  def optimizeLocalScoped_?() = optimizeLocal.get

  /**
   * Returns true if the actor was in the provider's cache and evicted successfully, else false.
   */
  private[akka] def evict(path: ActorPath): Boolean = actors.remove(path) ne null

  /**
   * Using (checking out) actor on a specific node.
   */
  def useActorOnNode(system: ActorSystem, remoteAddress: RemoteAddress, actorPath: String, actorFactory: () ⇒ Actor) {
    log.debug("[{}] Instantiating Actor [{}] on node [{}]", rootPath, actorPath, remoteAddress)

    val actorFactoryBytes =
      serialization.serialize(actorFactory) match {
        case Left(error)  ⇒ throw error
        case Right(bytes) ⇒ if (remoteSettings.ShouldCompressData) LZF.compress(bytes) else bytes
      }

    val command = RemoteSystemDaemonMessageProtocol.newBuilder
      .setMessageType(USE)
      .setActorPath(actorPath)
      .setPayload(ByteString.copyFrom(actorFactoryBytes))
      .build()

    val connectionFactory = () ⇒ actorFor(RootActorPath(remoteAddress) / remote.remoteDaemon.path.elements)

    // try to get the connection for the remote address, if not already there then create it
    val connection = remoteDaemonConnectionManager.putIfAbsent(remoteAddress, connectionFactory)

    sendCommandToRemoteNode(connection, command, withACK = true) // ensure we get an ACK on the USE command
  }

  private def sendCommandToRemoteNode(connection: ActorRef, command: RemoteSystemDaemonMessageProtocol, withACK: Boolean) {
    if (withACK) {
      try {
        val f = connection ? (command, remoteSettings.RemoteSystemDaemonAckTimeout)
        (try f.await.value catch { case _: FutureTimeoutException ⇒ None }) match {
          case Some(Right(receiver)) ⇒
            log.debug("Remote system command sent to [{}] successfully received", receiver)

          case Some(Left(cause)) ⇒
            log.error(cause, cause.toString)
            throw cause

          case None ⇒
            val error = new RemoteException("Remote system command to [%s] timed out".format(connection.path))
            log.error(error, error.toString)
            throw error
        }
      } catch {
        case e: Exception ⇒
          log.error(e, "Could not send remote system command to [{}] due to: {}", connection.path, e.toString)
          throw e
      }
    } else {
      connection ! command
    }
  }

  private[akka] def createDeathWatch(): DeathWatch = local.createDeathWatch() //FIXME Implement Remote DeathWatch, ticket ##1190

  private[akka] def ask(message: Any, recipient: ActorRef, within: Timeout): Future[Any] = local.ask(message, recipient, within)
}

/**
 * Remote ActorRef that is used when referencing the Actor on a different node than its "home" node.
 * This reference is network-aware (remembers its origin) and immutable.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
private[akka] class RemoteActorRef private[akka] (
  provider: ActorRefProvider,
  remote: RemoteSupport,
  val path: ActorPath,
  loader: Option[ClassLoader])
  extends InternalActorRef {

  // FIXME RK
  def getParent = Nobody
  def getChild(name: Iterator[String]) = Nobody

  @volatile
  private var running: Boolean = true

  def isTerminated: Boolean = !running

  def sendSystemMessage(message: SystemMessage): Unit = remote.send(message, None, this, loader)

  override def !(message: Any)(implicit sender: ActorRef = null): Unit = remote.send(message, Option(sender), this, loader)

  override def ?(message: Any)(implicit timeout: Timeout): Future[Any] = provider.ask(message, this, timeout)

  def suspend(): Unit = sendSystemMessage(Suspend())

  def resume(): Unit = sendSystemMessage(Resume())

  def stop(): Unit = sendSystemMessage(Terminate())

  def restart(cause: Throwable): Unit = sendSystemMessage(Recreate(cause))

  @throws(classOf[java.io.ObjectStreamException])
  private def writeReplace(): AnyRef = SerializedActorRef(path.toString)
}
