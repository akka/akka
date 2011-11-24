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

/**
 * Remote ActorRefProvider. Starts up actor on remote node and creates a RemoteActorRef representing it.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RemoteActorRefProvider(
  val settings: ActorSystem.Settings,
  val rootPath: ActorPath,
  val eventStream: EventStream,
  val dispatcher: MessageDispatcher,
  val scheduler: Scheduler) extends ActorRefProvider {

  val log = Logging(eventStream, "RemoteActorRefProvider")

  val local = new LocalActorRefProvider(settings, rootPath, eventStream, dispatcher, scheduler)

  def deathWatch = local.deathWatch
  def guardian = local.guardian
  def systemGuardian = local.systemGuardian
  def nodename = local.nodename
  def tempName = local.tempName

  @volatile
  var remote: Remote = _

  private val actors = new ConcurrentHashMap[String, AnyRef]

  @volatile
  private var remoteDaemonConnectionManager: RemoteConnectionManager = _

  def init(system: ActorSystemImpl) {
    local.init(system)
    remote = new Remote(system, nodename)
    remoteDaemonConnectionManager = new RemoteConnectionManager(system, remote)
    terminationFuture.onComplete(_ ⇒ remote.server.shutdown())
  }

  private[akka] def theOneWhoWalksTheBubblesOfSpaceTime: ActorRef = local.theOneWhoWalksTheBubblesOfSpaceTime
  private[akka] def terminationFuture = local.terminationFuture

  private[akka] def deployer: Deployer = local.deployer

  def defaultDispatcher = dispatcher
  def defaultTimeout = settings.ActorTimeout

  private[akka] def actorOf(system: ActorSystemImpl, props: Props, supervisor: ActorRef, name: String, systemService: Boolean): ActorRef =
    actorOf(system, props, supervisor, supervisor.path / name, systemService)

  private[akka] def actorOf(system: ActorSystemImpl, props: Props, supervisor: ActorRef, path: ActorPath, systemService: Boolean): ActorRef =
    if (systemService) local.actorOf(system, props, supervisor, path, systemService)
    else {
      val name = path.name
      val newFuture = Promise[ActorRef](5000)(defaultDispatcher) // FIXME is this proper timeout?

      actors.putIfAbsent(path.toString, newFuture) match { // we won the race -- create the actor and resolve the future
        case null ⇒
          val actor: ActorRef = try {
            deployer.lookupDeploymentFor(path.toString) match {
              case Some(DeploymentConfig.Deploy(_, _, routerType, nrOfInstances, DeploymentConfig.RemoteScope(remoteAddresses))) ⇒

                // FIXME move to AccrualFailureDetector as soon as we have the Gossiper up and running and remove the option to select impl in the akka.conf file since we only have one
                // val failureDetector = DeploymentConfig.failureDetectorTypeFor(failureDetectorType) match {
                //   case FailureDetectorType.NoOp                           ⇒ new NoOpFailureDetector
                //   case FailureDetectorType.RemoveConnectionOnFirstFailure ⇒ new RemoveConnectionOnFirstFailureFailureDetector
                //   case FailureDetectorType.BannagePeriod(timeToBan)       ⇒ new BannagePeriodFailureDetector(timeToBan)
                //   case FailureDetectorType.Custom(implClass)              ⇒ FailureDetector.createCustomFailureDetector(implClass)
                // }

                def isReplicaNode: Boolean = remoteAddresses exists { _ == system.address }

                //system.eventHandler.debug(this, "%s: Deploy Remote Actor with address [%s] connected to [%s]: isReplica(%s)".format(system.defaultAddress, address, remoteAddresses.mkString, isReplicaNode))

                if (isReplicaNode) {
                  // we are on one of the replica node for this remote actor
                  local.actorOf(system, props, supervisor, name, true) //FIXME systemService = true here to bypass Deploy, should be fixed when create-or-get is replaced by get-or-create
                } else {

                  // we are on the single "reference" node uses the remote actors on the replica nodes
                  val routerFactory: () ⇒ Router = DeploymentConfig.routerTypeFor(routerType) match {
                    case RouterType.Direct ⇒
                      if (remoteAddresses.size != 1) throw new ConfigurationException(
                        "Actor [%s] configured with Direct router must have exactly 1 remote node configured. Found [%s]"
                          .format(name, remoteAddresses.mkString(", ")))
                      () ⇒ new DirectRouter

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
                      () ⇒ new ScatterGatherFirstCompletedRouter()(defaultDispatcher, defaultTimeout)

                    case RouterType.LeastCPU          ⇒ sys.error("Router LeastCPU not supported yet")
                    case RouterType.LeastRAM          ⇒ sys.error("Router LeastRAM not supported yet")
                    case RouterType.LeastMessages     ⇒ sys.error("Router LeastMessages not supported yet")
                    case RouterType.Custom(implClass) ⇒ () ⇒ Routing.createCustomRouter(implClass)
                  }

                  val connections = (Map.empty[RemoteAddress, ActorRef] /: remoteAddresses) { (conns, a) ⇒
                    val remoteAddress = RemoteAddress(a.hostname, a.port)
                    conns + (remoteAddress -> RemoteActorRef(remote.system.provider, remote.server, remoteAddress, path, None))
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
        case actor: ActorRef   ⇒ actor
        case future: Future[_] ⇒ future.get.asInstanceOf[ActorRef]
      }
    }

  /**
   * Copied from LocalActorRefProvider...
   */
  // FIXME: implement supervision
  def actorOf(system: ActorSystem, props: RoutedProps, supervisor: ActorRef, name: String): ActorRef = {
    if (props.connectionManager.isEmpty) throw new ConfigurationException("RoutedProps used for creating actor [" + name + "] has zero connections configured; can't create a router")
    new RoutedActorRef(system, props, supervisor, name)
  }

  def actorFor(path: Iterable[String]): Option[ActorRef] = actors.get(ActorPath.join(path)) match {
    case null              ⇒ local.actorFor(path)
    case actor: ActorRef   ⇒ Some(actor)
    case future: Future[_] ⇒ Some(future.get.asInstanceOf[ActorRef])
  }

  // TODO remove me
  val optimizeLocal = new AtomicBoolean(true)
  def optimizeLocalScoped_?() = optimizeLocal.get

  /**
   * Returns true if the actor was in the provider's cache and evicted successfully, else false.
   */
  private[akka] def evict(path: String): Boolean = actors.remove(path) ne null

  private[akka] def serialize(actor: ActorRef): SerializedActorRef = actor match {
    case r: RemoteActorRef ⇒ new SerializedActorRef(r.remoteAddress, actor.path.toString)
    case other             ⇒ local.serialize(actor)
  }

  private[akka] def deserialize(actor: SerializedActorRef): Option[ActorRef] = {
    val remoteAddress = RemoteAddress(actor.hostname, actor.port)
    if (optimizeLocalScoped_? && remoteAddress == rootPath.remoteAddress) {
      local.actorFor(ActorPath.split(actor.path))
    } else {
      log.debug("{}: Creating RemoteActorRef with address [{}] connected to [{}]", rootPath.remoteAddress, actor.path, remoteAddress)
      Some(RemoteActorRef(remote.system.provider, remote.server, remoteAddress, rootPath / ActorPath.split(actor.path), None)) //Should it be None here
    }
  }

  /**
   * Using (checking out) actor on a specific node.
   */
  def useActorOnNode(system: ActorSystem, remoteAddress: RemoteAddress, actorPath: String, actorFactory: () ⇒ Actor) {
    log.debug("[{}] Instantiating Actor [{}] on node [{}]", rootPath, actorPath, remoteAddress)

    val actorFactoryBytes =
      system.serialization.serialize(actorFactory) match {
        case Left(error)  ⇒ throw error
        case Right(bytes) ⇒ if (remote.shouldCompressData) LZF.compress(bytes) else bytes
      }

    val command = RemoteSystemDaemonMessageProtocol.newBuilder
      .setMessageType(USE)
      .setActorPath(actorPath)
      .setPayload(ByteString.copyFrom(actorFactoryBytes))
      .build()

    val connectionFactory = () ⇒ deserialize(new SerializedActorRef(remoteAddress, remote.remoteDaemon.path.toString)).get

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
            log.debug("Remote system command sent to [{}] successfully received", receiver)

          case Some(Left(cause)) ⇒
            log.error(cause, cause.toString)
            throw cause

          case None ⇒
            val error = new RemoteException("Remote system command to [%s] timed out".format(connection.address))
            log.error(error, error.toString)
            throw error
        }
      } catch {
        case e: Exception ⇒
          log.error(e, "Could not send remote system command to [{}] due to: {}", connection.address, e.toString)
          throw e
      }
    } else {
      connection ! command
    }
  }

  private[akka] def createDeathWatch(): DeathWatch = local.createDeathWatch() //FIXME Implement Remote DeathWatch

  private[akka] def ask(message: Any, recipient: ActorRef, within: Timeout): Future[Any] = local.ask(message, recipient, within)

  private[akka] def tempPath = local.tempPath
}

/**
 * Remote ActorRef that is used when referencing the Actor on a different node than its "home" node.
 * This reference is network-aware (remembers its origin) and immutable.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
private[akka] case class RemoteActorRef private[akka] (
  provider: ActorRefProvider,
  remote: RemoteSupport,
  remoteAddress: RemoteAddress,
  path: ActorPath,
  loader: Option[ClassLoader])
  extends ActorRef with ScalaActorRef {

  @volatile
  private var running: Boolean = true

  def name = path.name

  def address = remoteAddress + path.toString

  def isTerminated: Boolean = !running

  protected[akka] def sendSystemMessage(message: SystemMessage): Unit = unsupported

  override def !(message: Any)(implicit sender: ActorRef = null): Unit = remote.send(message, Option(sender), remoteAddress, this, loader)

  override def ?(message: Any)(implicit timeout: Timeout): Future[Any] = provider.ask(message, this, timeout)

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
  private def writeReplace(): AnyRef = provider.serialize(this)

  def startsWatching(actorRef: ActorRef): ActorRef = unsupported //FIXME Implement

  def stopsWatching(actorRef: ActorRef): ActorRef = unsupported //FIXME Implement

  protected[akka] def restart(cause: Throwable): Unit = ()

  private def unsupported = throw new UnsupportedOperationException("Not supported for RemoteActorRef")
}
