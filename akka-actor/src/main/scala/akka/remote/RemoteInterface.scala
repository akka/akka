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

abstract class RemoteSupport(val app: AkkaApplication) extends RemoteServerModule with RemoteClientModule {

  def shutdown() {
    this.shutdownClientModule()
    this.shutdownServerModule()
    clear
  }

  protected[akka] override def notifyListeners(message: ⇒ Any): Unit = app.eventHandler.notify(message)

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
    start(app.defaultAddress.getAddress.getHostAddress,
      app.defaultAddress.getPort,
      None)

  /**
   *  Starts the server up
   */
  def start(loader: ClassLoader): RemoteServerModule =
    start(app.defaultAddress.getAddress.getHostAddress,
      app.defaultAddress.getPort,
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
    actorFor(address, hostname, port, None)

  def actorFor(address: String, hostname: String, port: Int, loader: ClassLoader): ActorRef =
    actorFor(address, hostname, port, Some(loader))

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

  protected[akka] def actorFor(address: String, hostname: String, port: Int, loader: Option[ClassLoader]): ActorRef

  protected[akka] def send[T](message: Any,
                              senderOption: Option[ActorRef],
                              remoteAddress: InetSocketAddress,
                              actorRef: ActorRef,
                              loader: Option[ClassLoader]): Unit
}
