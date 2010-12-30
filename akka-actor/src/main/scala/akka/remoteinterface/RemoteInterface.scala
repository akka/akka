/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.remoteinterface

import akka.japi.Creator
import java.net.InetSocketAddress
import akka.actor._
import akka.util._
import akka.dispatch.CompletableFuture
import akka.config.Config.{config, TIME_UNIT}
import java.util.concurrent.ConcurrentHashMap
import akka.AkkaException
import reflect.BeanProperty

trait RemoteModule extends Logging {
  val UUID_PREFIX        = "uuid:"

  def optimizeLocalScoped_?(): Boolean //Apply optimizations for remote operations in local scope
  protected[akka] def notifyListeners(message: => Any): Unit


  private[akka] def actors: ConcurrentHashMap[String, ActorRef]
  private[akka] def actorsByUuid: ConcurrentHashMap[String, ActorRef]
  private[akka] def actorsFactories: ConcurrentHashMap[String, () => ActorRef]
  private[akka] def typedActors: ConcurrentHashMap[String, AnyRef]
  private[akka] def typedActorsByUuid: ConcurrentHashMap[String, AnyRef]
  private[akka] def typedActorsFactories: ConcurrentHashMap[String, () => AnyRef]


  /** Lookup methods **/

  private[akka] def findActorById(id: String) : ActorRef = actors.get(id)

  private[akka] def findActorByUuid(uuid: String) : ActorRef = actorsByUuid.get(uuid)

  private[akka] def findActorFactory(id: String) : () => ActorRef = actorsFactories.get(id)

  private[akka] def findTypedActorById(id: String) : AnyRef = typedActors.get(id)

  private[akka] def findTypedActorFactory(id: String) : () => AnyRef = typedActorsFactories.get(id)

  private[akka] def findTypedActorByUuid(uuid: String) : AnyRef = typedActorsByUuid.get(uuid)

  private[akka] def findActorByIdOrUuid(id: String, uuid: String) : ActorRef = {
    var actorRefOrNull = if (id.startsWith(UUID_PREFIX)) findActorByUuid(id.substring(UUID_PREFIX.length))
                         else findActorById(id)
    if (actorRefOrNull eq null) actorRefOrNull = findActorByUuid(uuid)
    actorRefOrNull
  }

  private[akka] def findTypedActorByIdOrUuid(id: String, uuid: String) : AnyRef = {
    var actorRefOrNull = if (id.startsWith(UUID_PREFIX)) findTypedActorByUuid(id.substring(UUID_PREFIX.length))
                         else findTypedActorById(id)
    if (actorRefOrNull eq null) actorRefOrNull = findTypedActorByUuid(uuid)
    actorRefOrNull
  }
}

/**
 * Life-cycle events for RemoteClient.
 */
sealed trait RemoteClientLifeCycleEvent //TODO: REVISIT: Document change from RemoteClient to RemoteClientModule + remoteAddress
case class RemoteClientError(
  @BeanProperty cause: Throwable,
  @BeanProperty client: RemoteClientModule, remoteAddress: InetSocketAddress) extends RemoteClientLifeCycleEvent
case class RemoteClientDisconnected(
  @BeanProperty client: RemoteClientModule, remoteAddress: InetSocketAddress) extends RemoteClientLifeCycleEvent
case class RemoteClientConnected(
  @BeanProperty client: RemoteClientModule, remoteAddress: InetSocketAddress) extends RemoteClientLifeCycleEvent
case class RemoteClientStarted(
  @BeanProperty client: RemoteClientModule, remoteAddress: InetSocketAddress) extends RemoteClientLifeCycleEvent
case class RemoteClientShutdown(
  @BeanProperty client: RemoteClientModule, remoteAddress: InetSocketAddress) extends RemoteClientLifeCycleEvent
case class RemoteClientWriteFailed(
  @BeanProperty request: AnyRef,
  @BeanProperty cause: Throwable,
  @BeanProperty client: RemoteClientModule, remoteAddress: InetSocketAddress) extends RemoteClientLifeCycleEvent


/**
 *  Life-cycle events for RemoteServer.
 */
sealed trait RemoteServerLifeCycleEvent //TODO: REVISIT: Document change from RemoteServer to RemoteServerModule
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

/**
 * Thrown for example when trying to send a message using a RemoteClient that is either not started or shut down.
 */
class RemoteClientException private[akka] (message: String,
                                           @BeanProperty val client: RemoteClientModule,
                                           val remoteAddress: InetSocketAddress) extends AkkaException(message)

/**
 * Returned when a remote exception cannot be instantiated or parsed
 */
case class UnparsableException private[akka] (originalClassName: String,
                                              originalMessage: String) extends AkkaException(originalMessage)


abstract class RemoteSupport extends ListenerManagement with RemoteServerModule with RemoteClientModule {
  def shutdown {
    this.shutdownClientModule
    this.shutdownServerModule
    clear
  }


  /**
   * Creates a Client-managed ActorRef out of the Actor of the specified Class.
   * If the supplied host and port is identical of the configured local node, it will be a local actor
   * <pre>
   *   import Actor._
   *   val actor = actorOf(classOf[MyActor],"www.akka.io",2552)
   *   actor.start
   *   actor ! message
   *   actor.stop
   * </pre>
   * You can create and start the actor in one statement like this:
   * <pre>
   *   val actor = actorOf(classOf[MyActor],"www.akka.io",2552).start
   * </pre>
   */
  def actorOf(factory: => Actor, host: String, port: Int): ActorRef =
    ActorRegistry.remote.clientManagedActorOf(() => factory, host, port)

  /**
   * Creates a Client-managed ActorRef out of the Actor of the specified Class.
   * If the supplied host and port is identical of the configured local node, it will be a local actor
   * <pre>
   *   import Actor._
   *   val actor = actorOf(classOf[MyActor],"www.akka.io",2552)
   *   actor.start
   *   actor ! message
   *   actor.stop
   * </pre>
   * You can create and start the actor in one statement like this:
   * <pre>
   *   val actor = actorOf(classOf[MyActor],"www.akka.io",2552).start
   * </pre>
   */
  def actorOf(clazz: Class[_ <: Actor], host: String, port: Int): ActorRef = {
    import ReflectiveAccess.{ createInstance, noParams, noArgs }
    clientManagedActorOf(() =>
        createInstance[Actor](clazz.asInstanceOf[Class[_]], noParams, noArgs).getOrElse(
          throw new ActorInitializationException(
            "Could not instantiate Actor" +
            "\nMake sure Actor is NOT defined inside a class/trait," +
            "\nif so put it outside the class/trait, f.e. in a companion object," +
            "\nOR try to change: 'actorOf[MyActor]' to 'actorOf(new MyActor)'.")),
      host, port)
  }

  /**
   * Creates a Client-managed ActorRef out of the Actor of the specified Class.
   * If the supplied host and port is identical of the configured local node, it will be a local actor
   * <pre>
   *   import Actor._
   *   val actor = actorOf[MyActor]("www.akka.io",2552)
   *   actor.start
   *   actor ! message
   *   actor.stop
   * </pre>
   * You can create and start the actor in one statement like this:
   * <pre>
   *   val actor = actorOf[MyActor]("www.akka.io",2552).start
   * </pre>
   */
  def actorOf[T <: Actor : Manifest](host: String, port: Int): ActorRef = {
    import ReflectiveAccess.{ createInstance, noParams, noArgs }
    clientManagedActorOf(() =>
      createInstance[Actor](manifest[T].erasure.asInstanceOf[Class[_]], noParams, noArgs).getOrElse(
        throw new ActorInitializationException(
          "Could not instantiate Actor" +
          "\nMake sure Actor is NOT defined inside a class/trait," +
          "\nif so put it outside the class/trait, f.e. in a companion object," +
          "\nOR try to change: 'actorOf[MyActor]' to 'actorOf(new MyActor)'.")),
      host, port)
  }

  protected override def manageLifeCycleOfListeners = false
  protected[akka] override def notifyListeners(message: => Any): Unit = super.notifyListeners(message)

  private[akka] val actors               = new ConcurrentHashMap[String, ActorRef]
  private[akka] val actorsByUuid         = new ConcurrentHashMap[String, ActorRef]
  private[akka] val actorsFactories      = new ConcurrentHashMap[String, () => ActorRef]
  private[akka] val typedActors          = new ConcurrentHashMap[String, AnyRef]
  private[akka] val typedActorsByUuid    = new ConcurrentHashMap[String, AnyRef]
  private[akka] val typedActorsFactories = new ConcurrentHashMap[String, () => AnyRef]

  def clear {
    List(actors,actorsByUuid,actorsFactories,typedActors,typedActorsByUuid,typedActorsFactories) foreach (_.clear)
  }
}

/**
 * This is the interface for the RemoteServer functionality, it's used in ActorRegistry.remote
 */
trait RemoteServerModule extends RemoteModule {
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
  def start(host: String = ReflectiveAccess.Remote.configDefaultAddress.getHostName,
            port: Int = ReflectiveAccess.Remote.configDefaultAddress.getPort,
            loader: Option[ClassLoader] = None): RemoteServerModule

  /**
   *  Shuts the server down
   */
  def shutdownServerModule: Unit

  /**
   *  Register typed actor by interface name.
   */
  def registerTypedActor(intfClass: Class[_], typedActor: AnyRef) : Unit = registerTypedActor(intfClass.getName, typedActor)

  /**
   * Register remote typed actor by a specific id.
   * @param id custom actor id
   * @param typedActor typed actor to register
   */
  def registerTypedActor(id: String, typedActor: AnyRef): Unit

  /**
   * Register typed actor by interface name.
   */
  def registerTypedPerSessionActor(intfClass: Class[_], factory: => AnyRef) : Unit = registerTypedActor(intfClass.getName, factory)

  /**
   * Register typed actor by interface name.
   * Java API
   */
  def registerTypedPerSessionActor(intfClass: Class[_], factory: Creator[AnyRef]) : Unit = registerTypedActor(intfClass.getName, factory)

  /**
   * Register remote typed actor by a specific id.
   * @param id custom actor id
   * @param typedActor typed actor to register
   */
  def registerTypedPerSessionActor(id: String, factory: => AnyRef): Unit

  /**
   * Register remote typed actor by a specific id.
   * @param id custom actor id
   * @param typedActor typed actor to register
   * Java API
   */
  def registerTypedPerSessionActor(id: String, factory: Creator[AnyRef]): Unit = registerTypedPerSessionActor(id, factory.create)

  /**
   * Register Remote Actor by the Actor's 'id' field. It starts the Actor if it is not started already.
   */
  def register(actorRef: ActorRef): Unit = register(actorRef.id, actorRef)

  /**
   *  Register Remote Actor by the Actor's uuid field. It starts the Actor if it is not started already.
   */
  def registerByUuid(actorRef: ActorRef): Unit

  /**
   *  Register Remote Actor by a specific 'id' passed as argument.
   * <p/>
   * NOTE: If you use this method to register your remote actor then you must unregister the actor by this ID yourself.
   */
  def register(id: String, actorRef: ActorRef): Unit

  /**
   * Register Remote Session Actor by a specific 'id' passed as argument.
   * <p/>
   * NOTE: If you use this method to register your remote actor then you must unregister the actor by this ID yourself.
   */
  def registerPerSession(id: String, factory: => ActorRef): Unit

  /**
   * Register Remote Session Actor by a specific 'id' passed as argument.
   * <p/>
   * NOTE: If you use this method to register your remote actor then you must unregister the actor by this ID yourself.
   * Java API
   */
  def registerPerSession(id: String, factory: Creator[ActorRef]): Unit = registerPerSession(id, factory.create)

  /**
   * Unregister Remote Actor that is registered using its 'id' field (not custom ID).
   */
  def unregister(actorRef: ActorRef): Unit

  /**
   * Unregister Remote Actor by specific 'id'.
   * <p/>
   * NOTE: You need to call this method if you have registered an actor by a custom ID.
   */
  def unregister(id: String): Unit

  /**
   * Unregister Remote Actor by specific 'id'.
   * <p/>
   * NOTE: You need to call this method if you have registered an actor by a custom ID.
   */
  def unregisterPerSession(id: String): Unit

  /**
   * Unregister Remote Typed Actor by specific 'id'.
   * <p/>
   * NOTE: You need to call this method if you have registered an actor by a custom ID.
   */
  def unregisterTypedActor(id: String): Unit

  /**
  * Unregister Remote Typed Actor by specific 'id'.
  * <p/>
  * NOTE: You need to call this method if you have registered an actor by a custom ID.
  */
 def unregisterTypedPerSessionActor(id: String): Unit
}

trait RemoteClientModule extends RemoteModule { self: RemoteModule =>

  def actorFor(classNameOrServiceId: String, hostname: String, port: Int): ActorRef =
    actorFor(classNameOrServiceId, classNameOrServiceId, Actor.TIMEOUT, hostname, port, None)

  def actorFor(classNameOrServiceId: String, hostname: String, port: Int, loader: ClassLoader): ActorRef =
    actorFor(classNameOrServiceId, classNameOrServiceId, Actor.TIMEOUT, hostname, port, Some(loader))

  def actorFor(serviceId: String, className: String, hostname: String, port: Int): ActorRef =
    actorFor(serviceId, className, Actor.TIMEOUT, hostname, port, None)

  def actorFor(serviceId: String, className: String, hostname: String, port: Int, loader: ClassLoader): ActorRef =
    actorFor(serviceId, className, Actor.TIMEOUT, hostname, port, Some(loader))

  def actorFor(classNameOrServiceId: String, timeout: Long, hostname: String, port: Int): ActorRef =
    actorFor(classNameOrServiceId, classNameOrServiceId, timeout, hostname, port, None)

  def actorFor(classNameOrServiceId: String, timeout: Long, hostname: String, port: Int, loader: ClassLoader): ActorRef =
    actorFor(classNameOrServiceId, classNameOrServiceId, timeout, hostname, port, Some(loader))

  def actorFor(serviceId: String, className: String, timeout: Long, hostname: String, port: Int): ActorRef =
    actorFor(serviceId, className, timeout, hostname, port, None)

  def typedActorFor[T](intfClass: Class[T], serviceIdOrClassName: String, hostname: String, port: Int): T =
    typedActorFor(intfClass, serviceIdOrClassName, serviceIdOrClassName, Actor.TIMEOUT, hostname, port, None)

  def typedActorFor[T](intfClass: Class[T], serviceIdOrClassName: String, timeout: Long, hostname: String, port: Int): T =
    typedActorFor(intfClass, serviceIdOrClassName, serviceIdOrClassName, timeout, hostname, port, None)

  def typedActorFor[T](intfClass: Class[T], serviceIdOrClassName: String, timeout: Long, hostname: String, port: Int, loader: ClassLoader): T =
    typedActorFor(intfClass, serviceIdOrClassName, serviceIdOrClassName, timeout, hostname, port, Some(loader))

  def typedActorFor[T](intfClass: Class[T], serviceId: String, implClassName: String, timeout: Long, hostname: String, port: Int, loader: ClassLoader): T =
    typedActorFor(intfClass, serviceId, implClassName, timeout, hostname, port, Some(loader))

  def clientManagedActorOf(factory: () => Actor, host: String, port: Int): ActorRef


  /**
   * Clean-up all open connections.
   */
  def shutdownClientModule: Unit

  /**
   * Shuts down a specific client connected to the supplied remote address returns true if successful
   */
  def shutdownClientConnection(address: InetSocketAddress): Boolean

  /**
   * Restarts a specific client connected to the supplied remote address, but only if the client is not shut down
   */
  def restartClientConnection(address: InetSocketAddress): Boolean

  /** Methods that needs to be implemented by a transport **/

  protected[akka] def typedActorFor[T](intfClass: Class[T], serviceId: String, implClassName: String, timeout: Long, host: String, port: Int, loader: Option[ClassLoader]): T

  protected[akka] def actorFor(serviceId: String, className: String, timeout: Long, hostname: String, port: Int, loader: Option[ClassLoader]): ActorRef

  protected[akka] def send[T](message: Any,
                              senderOption: Option[ActorRef],
                              senderFuture: Option[CompletableFuture[T]],
                              remoteAddress: InetSocketAddress,
                              timeout: Long,
                              isOneWay: Boolean,
                              actorRef: ActorRef,
                              typedActorInfo: Option[Tuple2[String, String]],
                              actorType: ActorType,
                              loader: Option[ClassLoader]): Option[CompletableFuture[T]]

  private[akka] def registerSupervisorForActor(actorRef: ActorRef): ActorRef

  private[akka] def deregisterSupervisorForActor(actorRef: ActorRef): ActorRef

    private[akka] def registerClientManagedActor(hostname: String, port: Int, uuid: Uuid): Unit

    private[akka] def unregisterClientManagedActor(hostname: String, port: Int, uuid: Uuid): Unit
}