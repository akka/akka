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

trait RemoteModule extends Logging {
  def optimizeLocalScoped_?(): Boolean //Apply optimizations for remote operations in local scope
  protected[akka] def notifyListeners(message: => Any): Unit


  private[akka] def actors: ConcurrentHashMap[String, ActorRef]
  private[akka] def actorsByUuid: ConcurrentHashMap[String, ActorRef]
  private[akka] def actorsFactories: ConcurrentHashMap[String, () => ActorRef]
  private[akka] def typedActors: ConcurrentHashMap[String, AnyRef]
  private[akka] def typedActorsByUuid: ConcurrentHashMap[String, AnyRef]
  private[akka] def typedActorsFactories: ConcurrentHashMap[String, () => AnyRef]
}


abstract class RemoteSupport extends ListenerManagement with RemoteServerModule with RemoteClientModule {
  def shutdown {
    this.shutdownClientModule
    this.shutdownServerModule
    clear
  }
  protected override def manageLifeCycleOfListeners = false
  protected[akka] override def notifyListeners(message: => Any): Unit = super.notifyListeners(message)

  private[akka] val actors = new ConcurrentHashMap[String, ActorRef]
  private[akka] val actorsByUuid = new ConcurrentHashMap[String, ActorRef]
  private[akka] val actorsFactories = new ConcurrentHashMap[String, () => ActorRef]
  private[akka] val typedActors = new ConcurrentHashMap[String, AnyRef]
  private[akka] val typedActorsByUuid = new ConcurrentHashMap[String, AnyRef]
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
   * Gets the current hostname of the server instance
   */
  def hostname: String

  /**
   *  Gets the current port of the server instance
   */
  def port: Int

  /**
   *  Starts the server up
   */
  def start(host: String = ReflectiveAccess.Remote.HOSTNAME, port: Int = ReflectiveAccess.Remote.PORT, loader: Option[ClassLoader] = None): RemoteServerModule

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

  def clientManagedActorOf(clazz: Class[_ <: Actor], host: String, port: Int, timeout: Long): ActorRef

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

    /**
     * Clean-up all open connections.
     */
    def shutdownClientModule: Unit

    private[akka] def registerClientManagedActor(hostname: String, port: Int, uuid: Uuid): Unit

    private[akka] def unregisterClientManagedActor(hostname: String, port: Int, uuid: Uuid): Unit
}