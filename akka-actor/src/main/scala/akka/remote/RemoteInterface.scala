/**
 * Copyright (C) 2009-2010 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.japi.Creator
import akka.actor._
import DeploymentConfig._
import akka.util._
import akka.dispatch.Promise
import akka.serialization._
import akka.{ AkkaException, AkkaApplication }

import scala.reflect.BeanProperty

import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.io.{ PrintWriter, PrintStream }
import java.lang.reflect.InvocationTargetException

class RemoteException(message: String) extends AkkaException(message)

trait RemoteService {
  def server: RemoteSupport
  def address: InetSocketAddress
}

trait RemoteModule {
  val UUID_PREFIX = "uuid:".intern

  def optimizeLocalScoped_?(): Boolean //Apply optimizations for remote operations in local scope
  protected[akka] def notifyListeners(message: ⇒ Any): Unit

  private[akka] def actors: ConcurrentHashMap[String, ActorRef] // FIXME need to invalidate this cache on replication
  private[akka] def actorsByUuid: ConcurrentHashMap[String, ActorRef] // FIXME remove actorsByUuid map?
  private[akka] def actorsFactories: ConcurrentHashMap[String, () ⇒ ActorRef] // FIXME what to do wit actorsFactories map?

  private[akka] def findActorByAddress(address: String): ActorRef = actors.get(address)

  private[akka] def findActorByUuid(uuid: String): ActorRef = actorsByUuid.get(uuid)

  private[akka] def findActorFactory(address: String): () ⇒ ActorRef = actorsFactories.get(address)

  private[akka] def findActorByAddressOrUuid(address: String, uuid: String): ActorRef = {
    var actorRefOrNull = if (address.startsWith(UUID_PREFIX)) findActorByUuid(address.substring(UUID_PREFIX.length))
    else findActorByAddress(address)
    if (actorRefOrNull eq null) actorRefOrNull = findActorByUuid(uuid)
    actorRefOrNull
  }

  /*
  private[akka] def findActorByAddress(address: String): ActorRef = {
    val cachedActorRef = actors.get(address)
    if (cachedActorRef ne null) cachedActorRef
    else {
      val actorRef =
        Deployer.lookupDeploymentFor(address) match {
          case Some(Deploy(_, router, _, Cluster(home, _, _))) ⇒

            if (DeploymentConfig.isHomeNode(home)) { // on home node
              Actor.registry.actorFor(address) match { // try to look up in actor registry
                case Some(actorRef) ⇒ // in registry -> DONE
                  actorRef
                case None ⇒ // not in registry -> check out as 'ref' from cluster (which puts it in actor registry for next time around)
                  Actor.cluster.ref(address, DeploymentConfig.routerTypeFor(router))
              }
            } else throw new IllegalActorStateException("Trying to look up remote actor on non-home node. FIXME: fix this behavior")

          case Some(Deploy(_, _, _, Local)) ⇒
            Actor.registry.actorFor(address).getOrElse(throw new IllegalActorStateException("Could not lookup locally deployed actor in actor registry"))

          case _ ⇒
            actors.get(address) // FIXME do we need to fall back to local here? If it is not clustered then it should not be a remote actor in the first place. Throw exception.
        }

      actors.put(address, actorRef) // cache it for next time around
      actorRef
    }
  }

  private[akka] def findActorByUuid(uuid: String): ActorRef = actorsByUuid.get(uuid)

  private[akka] def findActorFactory(address: String): () ⇒ ActorRef = actorsFactories.get(address)

  private[akka] def findActorByAddressOrUuid(address: String, uuid: String): ActorRef = {
    // find by address
    var actorRefOrNull =
      if (address.startsWith(UUID_PREFIX)) findActorByUuid(address.substring(UUID_PREFIX.length)) // FIXME remove lookup by UUID? probably
      else findActorByAddress(address)
    // find by uuid
    if (actorRefOrNull eq null) actorRefOrNull = findActorByUuid(uuid)
    actorRefOrNull
  }
  */
}

/**
 * Remote life-cycle events.
 */
sealed trait RemoteLifeCycleEvent

/**
 * Life-cycle events for RemoteClient.
 */
trait RemoteClientLifeCycleEvent extends RemoteLifeCycleEvent {
  def remoteAddress: InetSocketAddress
}

case class RemoteClientError(
  @BeanProperty cause: Throwable,
  @BeanProperty client: RemoteClientModule,
  @BeanProperty remoteAddress: InetSocketAddress) extends RemoteClientLifeCycleEvent

case class RemoteClientDisconnected(
  @BeanProperty client: RemoteClientModule,
  @BeanProperty remoteAddress: InetSocketAddress) extends RemoteClientLifeCycleEvent

case class RemoteClientConnected(
  @BeanProperty client: RemoteClientModule,
  @BeanProperty remoteAddress: InetSocketAddress) extends RemoteClientLifeCycleEvent

case class RemoteClientStarted(
  @BeanProperty client: RemoteClientModule,
  @BeanProperty remoteAddress: InetSocketAddress) extends RemoteClientLifeCycleEvent

case class RemoteClientShutdown(
  @BeanProperty client: RemoteClientModule,
  @BeanProperty remoteAddress: InetSocketAddress) extends RemoteClientLifeCycleEvent

case class RemoteClientWriteFailed(
  @BeanProperty request: AnyRef,
  @BeanProperty cause: Throwable,
  @BeanProperty client: RemoteClientModule,
  @BeanProperty remoteAddress: InetSocketAddress) extends RemoteClientLifeCycleEvent

/**
 *  Life-cycle events for RemoteServer.
 */
trait RemoteServerLifeCycleEvent extends RemoteLifeCycleEvent

case class RemoteServerStarted(
  @BeanProperty val server: RemoteServerModule) extends RemoteServerLifeCycleEvent
case class RemoteServerShutdown(
  @BeanProperty val server: RemoteServerModule) extends RemoteServerLifeCycleEvent
case class RemoteServerError(
  @BeanProperty val cause: Throwable,
  @BeanProperty val server: RemoteServerModule) extends RemoteServerLifeCycleEvent
case class RemoteServerClientConnected(
  @BeanProperty val server: RemoteServerModule,
  @BeanProperty val clientAddress: Option[InetSocketAddress]) extends RemoteServerLifeCycleEvent
case class RemoteServerClientDisconnected(
  @BeanProperty val server: RemoteServerModule,
  @BeanProperty val clientAddress: Option[InetSocketAddress]) extends RemoteServerLifeCycleEvent
case class RemoteServerClientClosed(
  @BeanProperty val server: RemoteServerModule,
  @BeanProperty val clientAddress: Option[InetSocketAddress]) extends RemoteServerLifeCycleEvent
case class RemoteServerWriteFailed(
  @BeanProperty request: AnyRef,
  @BeanProperty cause: Throwable,
  @BeanProperty server: RemoteServerModule,
  @BeanProperty clientAddress: Option[InetSocketAddress]) extends RemoteServerLifeCycleEvent

/**
 * Thrown for example when trying to send a message using a RemoteClient that is either not started or shut down.
 */
class RemoteClientException private[akka] (
  message: String,
  @BeanProperty val client: RemoteClientModule,
  val remoteAddress: InetSocketAddress, cause: Throwable = null) extends AkkaException(message, cause)

/**
 * Thrown when the remote server actor dispatching fails for some reason.
 */
class RemoteServerException private[akka] (message: String) extends AkkaException(message)

/**
 * Thrown when a remote exception sent over the wire cannot be loaded and instantiated
 */
case class CannotInstantiateRemoteExceptionDueToRemoteProtocolParsingErrorException private[akka] (cause: Throwable, originalClassName: String, originalMessage: String)
  extends AkkaException("\nParsingError[%s]\nOriginalException[%s]\nOriginalMessage[%s]"
    .format(cause.toString, originalClassName, originalMessage)) {
  override def printStackTrace = cause.printStackTrace
  override def printStackTrace(printStream: PrintStream) = cause.printStackTrace(printStream)
  override def printStackTrace(printWriter: PrintWriter) = cause.printStackTrace(printWriter)
}

abstract class RemoteSupport(val app: AkkaApplication) extends ListenerManagement with RemoteServerModule with RemoteClientModule {

  val eventHandler: ActorRef = {
    implicit object format extends StatelessActorFormat[RemoteEventHandler]
    val clazz = classOf[RemoteEventHandler]
    val handler = new LocalActorRef(app, Props(clazz), clazz.getName, true)
    // add the remote client and server listener that pipes the events to the event handler system
    addListener(handler)
    handler
  }

  def shutdown() {
    eventHandler.stop()
    removeListener(eventHandler)
    this.shutdownClientModule()
    this.shutdownServerModule()
    clear
  }

  protected override def manageLifeCycleOfListeners = false
  protected[akka] override def notifyListeners(message: ⇒ Any): Unit = super.notifyListeners(message)

  private[akka] val actors = new ConcurrentHashMap[String, ActorRef]
  private[akka] val actorsByUuid = new ConcurrentHashMap[String, ActorRef]
  private[akka] val actorsFactories = new ConcurrentHashMap[String, () ⇒ ActorRef]

  def clear {
    actors.clear
    actorsByUuid.clear
    actorsFactories.clear
  }
}

/**
 * This is the interface for the RemoteServer functionality, it's used in Actor.remote
 */
trait RemoteServerModule extends RemoteModule { this: RemoteSupport ⇒
  protected val guard = new ReentrantGuard

  /**
   * Signals whether the server is up and running or not
   */
  def isRunning: Boolean

  /**
   *  Gets the name of the server instance
   */
  def name: String

  /**
   * Gets the address of the server instance
   */
  def address: InetSocketAddress

  /**
   *  Starts the server up
   */
  def start(): RemoteServerModule =
    start(app.reflective.RemoteModule.configDefaultAddress.getAddress.getHostAddress,
      app.reflective.RemoteModule.configDefaultAddress.getPort,
      None)

  /**
   *  Starts the server up
   */
  def start(loader: ClassLoader): RemoteServerModule =
    start(app.reflective.RemoteModule.configDefaultAddress.getAddress.getHostAddress,
      app.reflective.RemoteModule.configDefaultAddress.getPort,
      Option(loader))

  /**
   *  Starts the server up
   */
  def start(host: String, port: Int): RemoteServerModule =
    start(host, port, None)

  /**
   *  Starts the server up
   */
  def start(host: String, port: Int, loader: ClassLoader): RemoteServerModule =
    start(host, port, Option(loader))

  /**
   *  Starts the server up
   */
  def start(host: String, port: Int, loader: Option[ClassLoader]): RemoteServerModule

  /**
   *  Shuts the server down
   */
  def shutdownServerModule(): Unit

  /**
   * Register Remote Actor by the Actor's 'id' field. It starts the Actor if it is not started already.
   */
  def register(actorRef: ActorRef): Unit = register(actorRef.address, actorRef)

  /**
   *  Register Remote Actor by the Actor's uuid field. It starts the Actor if it is not started already.
   */
  def registerByUuid(actorRef: ActorRef): Unit

  /**
   *  Register Remote Actor by a specific 'id' passed as argument. The actor is registered by UUID rather than ID
   *  when prefixing the handle with the “uuid:” protocol.
   * <p/>
   * NOTE: If you use this method to register your remote actor then you must unregister the actor by this ID yourself.
   */
  def register(address: String, actorRef: ActorRef): Unit

  /**
   * Register Remote Session Actor by a specific 'id' passed as argument.
   * <p/>
   * NOTE: If you use this method to register your remote actor then you must unregister the actor by this ID yourself.
   */
  def registerPerSession(address: String, factory: ⇒ ActorRef): Unit

  /**
   * Register Remote Session Actor by a specific 'id' passed as argument.
   * <p/>
   * NOTE: If you use this method to register your remote actor then you must unregister the actor by this ID yourself.
   * Java API
   */
  def registerPerSession(address: String, factory: Creator[ActorRef]): Unit = registerPerSession(address, factory.create)

  /**
   * Unregister Remote Actor that is registered using its 'id' field (not custom ID).
   */
  def unregister(actorRef: ActorRef): Unit

  /**
   * Unregister Remote Actor by specific 'id'.
   * <p/>
   * NOTE: You need to call this method if you have registered an actor by a custom ID.
   */
  def unregister(address: String): Unit

  /**
   * Unregister Remote Actor by specific 'id'.
   * <p/>
   * NOTE: You need to call this method if you have registered an actor by a custom ID.
   */
  def unregisterPerSession(address: String): Unit
}

trait RemoteClientModule extends RemoteModule { self: RemoteSupport ⇒

  def actorFor(address: String, hostname: String, port: Int): ActorRef =
    actorFor(address, app.AkkaConfig.ActorTimeoutMillis, hostname, port, None)

  def actorFor(address: String, hostname: String, port: Int, loader: ClassLoader): ActorRef =
    actorFor(address, app.AkkaConfig.ActorTimeoutMillis, hostname, port, Some(loader))

  def actorFor(address: String, timeout: Long, hostname: String, port: Int): ActorRef =
    actorFor(address, timeout, hostname, port, None)

  def actorFor(address: String, timeout: Long, hostname: String, port: Int, loader: ClassLoader): ActorRef =
    actorFor(address, timeout, hostname, port, Some(loader))

  /**
   * Clean-up all open connections.
   */
  def shutdownClientModule(): Unit

  /**
   * Shuts down a specific client connected to the supplied remote address returns true if successful
   */
  def shutdownClientConnection(address: InetSocketAddress): Boolean

  /**
   * Restarts a specific client connected to the supplied remote address, but only if the client is not shut down
   */
  def restartClientConnection(address: InetSocketAddress): Boolean

  /** Methods that needs to be implemented by a transport **/

  protected[akka] def actorFor(address: String, timeout: Long, hostname: String, port: Int, loader: Option[ClassLoader]): ActorRef

  protected[akka] def send[T](message: Any,
                              senderOption: Option[ActorRef],
                              senderFuture: Option[Promise[T]],
                              remoteAddress: InetSocketAddress,
                              isOneWay: Boolean,
                              actorRef: ActorRef,
                              loader: Option[ClassLoader]): Option[Promise[T]]
}
