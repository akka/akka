/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.actor

import Actor._
import se.scalablesolutions.akka.config.FaultHandlingStrategy
import se.scalablesolutions.akka.remote.protocol.RemoteProtocol.RemoteRequestProtocol
import se.scalablesolutions.akka.remote.{MessageSerializer, RemoteClient, RemoteRequestProtocolIdFactory}
import se.scalablesolutions.akka.dispatch.{MessageDispatcher, Future, CompletableFuture}
import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.serialization.Serializer
import se.scalablesolutions.akka.util._

import org.codehaus.aspectwerkz.joinpoint.{MethodRtti, JoinPoint}
import org.codehaus.aspectwerkz.proxy.Proxy
import org.codehaus.aspectwerkz.annotation.{Aspect, Around}

import java.net.InetSocketAddress
import java.lang.reflect.{InvocationTargetException, Method}

object Annotations {
  import se.scalablesolutions.akka.actor.annotation._
  val transactionrequired =    classOf[transactionrequired]
  val prerestart =             classOf[prerestart]
  val postrestart =            classOf[postrestart]
  val shutdown =               classOf[shutdown]
  val inittransactionalstate = classOf[inittransactionalstate]
}

/**
 * Configuration factory for Active Objects.
 *
 * FIXDOC: document ActiveObjectConfiguration
 */
final class ActiveObjectConfiguration {
  private[akka] var _timeout: Long = Actor.TIMEOUT
  private[akka] var _restartCallbacks: Option[RestartCallbacks] = None
  private[akka] var _shutdownCallback: Option[ShutdownCallback] = None
  private[akka] var _transactionRequired = false
  private[akka] var _host: Option[InetSocketAddress] = None
  private[akka] var _messageDispatcher: Option[MessageDispatcher] = None

  def timeout(timeout: Long) : ActiveObjectConfiguration = {
    _timeout = timeout
    this
  }

  def restartCallbacks(pre: String, post: String) : ActiveObjectConfiguration = {
    _restartCallbacks = Some(new RestartCallbacks(pre, post))
    this
  }

  def shutdownCallback(down: String) : ActiveObjectConfiguration = {
    _shutdownCallback = Some(new ShutdownCallback(down))
    this
  }

  def makeTransactionRequired() : ActiveObjectConfiguration = {
    _transactionRequired = true;
    this
  }

  def makeRemote(hostname: String, port: Int) : ActiveObjectConfiguration = {
    _host = Some(new InetSocketAddress(hostname, port))
    this
  }

  def dispatcher(messageDispatcher: MessageDispatcher) : ActiveObjectConfiguration = {
    _messageDispatcher = Some(messageDispatcher)
    this
  }
}

/**
 * Holds RTTI (runtime type information) for the Active Object, f.e. current 'sender'
 * reference, the 'senderFuture' reference etc.
 * <p/>
 * In order to make use of this context you have to create a member field in your
 * Active Object that has the type 'ActiveObjectContext', then an instance will
 * be injected for you to use.
 * <p/>
 * This class does not contain static information but is updated by the runtime system
 * at runtime.
 * <p/>
 * Here is an example of usage:
 * <pre>
 * class Ping {
 *   // This context will be injected, holds RTTI (runtime type information)
 *   // for the current message send
 *   private ActiveObjectContext context = null;
 *
 *   public void hit(int count) {
 *     Pong pong = (Pong) context.getSender();
 *     pong.hit(count++)
 *   }
 * }
 * </pre>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
final class ActiveObjectContext {
  private[akka] var _sender: AnyRef = _
  private[akka] var _senderFuture: CompletableFuture[Any] = _

  /**
   * Returns the current sender Active Object reference.
   * Scala style getter.
   */
  def sender: AnyRef = {
    if (_sender eq null) throw new IllegalActorStateException("Sender reference should not be null.")
    else _sender
  }

  /**
   * Returns the current sender Active Object reference.
   * Java style getter.
   */
   def getSender: AnyRef = {
     if (_sender eq null) throw new IllegalActorStateException("Sender reference should not be null.")
     else _sender
   }

  /**
   * Returns the current sender future Active Object reference.
   * Scala style getter.
   */
  def senderFuture: Option[CompletableFuture[Any]] = if (_senderFuture eq null) None else Some(_senderFuture)

  /**
   * Returns the current sender future Active Object reference.
   * Java style getter.
   * This method returns 'null' if the sender future is not available.
   */
  def getSenderFuture = _senderFuture
}

/**
 * Internal helper class to help pass the contextual information between threads.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
private[akka] object ActiveObjectContext {
  import scala.util.DynamicVariable
  private[actor] val sender =       new DynamicVariable[AnyRef](null)
  private[actor] val senderFuture = new DynamicVariable[CompletableFuture[Any]](null)
}

/**
 * Factory class for creating Active Objects out of plain POJOs and/or POJOs with interfaces.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object ActiveObject extends Logging {
  import Actor.actorOf

  val AKKA_CAMEL_ROUTING_SCHEME = "akka"
  private[actor] val AW_PROXY_PREFIX = "$$ProxiedByAW".intern

  def newInstance[T](target: Class[T], timeout: Long): T =
    newInstance(target, actorOf(new Dispatcher(false)), None, timeout)

  def newInstance[T](target: Class[T]): T =
    newInstance(target, actorOf(new Dispatcher(false)), None, Actor.TIMEOUT)

  def newInstance[T](intf: Class[T], target: AnyRef, timeout: Long): T =
    newInstance(intf, target, actorOf(new Dispatcher(false)), None, timeout)

  def newInstance[T](intf: Class[T], target: AnyRef): T =
    newInstance(intf, target, actorOf(new Dispatcher(false)), None, Actor.TIMEOUT)

  def newRemoteInstance[T](target: Class[T], timeout: Long, hostname: String, port: Int): T =
    newInstance(target, actorOf(new Dispatcher(false)), Some(new InetSocketAddress(hostname, port)), timeout)

  def newRemoteInstance[T](target: Class[T], hostname: String, port: Int): T =
    newInstance(target, actorOf(new Dispatcher(false)), Some(new InetSocketAddress(hostname, port)), Actor.TIMEOUT)

  def newInstance[T](target: Class[T], config: ActiveObjectConfiguration): T = {
    val actor = actorOf(new Dispatcher(config._transactionRequired, config._restartCallbacks, config._shutdownCallback))
     if (config._messageDispatcher.isDefined) {
       actor.dispatcher = config._messageDispatcher.get
     }
     newInstance(target, actor, config._host, config._timeout)
  }

  def newInstance[T](intf: Class[T], target: AnyRef, config: ActiveObjectConfiguration): T = {
    val actor = actorOf(new Dispatcher(config._transactionRequired, config._restartCallbacks, config._shutdownCallback))
     if (config._messageDispatcher.isDefined) {
       actor.dispatcher = config._messageDispatcher.get
     }
     newInstance(intf, target, actor, config._host, config._timeout)
  }

  @deprecated("use newInstance(target: Class[T], config: ActiveObjectConfiguration) instead")
  def newInstance[T](target: Class[T], timeout: Long, restartCallbacks: Option[RestartCallbacks]): T =
    newInstance(target, actorOf(new Dispatcher(false, restartCallbacks)), None, timeout)

  @deprecated("use newInstance(intf: Class[T], target: AnyRef, config: ActiveObjectConfiguration) instead")
  def newInstance[T](intf: Class[T], target: AnyRef, timeout: Long, restartCallbacks: Option[RestartCallbacks]): T =
    newInstance(intf, target, actorOf(new Dispatcher(false, restartCallbacks)), None, timeout)

  @deprecated("use newInstance(target: Class[T], config: ActiveObjectConfiguration) instead")
  def newInstance[T](target: Class[T], timeout: Long, transactionRequired: Boolean): T =
    newInstance(target, actorOf(new Dispatcher(transactionRequired, None)), None, timeout)

  @deprecated("use newInstance(target: Class[T], config: ActiveObjectConfiguration) instead")
  def newInstance[T](target: Class[T], timeout: Long, transactionRequired: Boolean, restartCallbacks: Option[RestartCallbacks]): T =
    newInstance(target, actorOf(new Dispatcher(transactionRequired, restartCallbacks)), None, timeout)

  @deprecated("use newInstance(intf: Class[T], target: AnyRef, config: ActiveObjectConfiguration) instead")
  def newInstance[T](intf: Class[T], target: AnyRef, timeout: Long, transactionRequired: Boolean): T =
    newInstance(intf, target, actorOf(new Dispatcher(transactionRequired, None)), None, timeout)

  @deprecated("use newInstance(intf: Class[T], target: AnyRef, config: ActiveObjectConfiguration) instead")
  def newInstance[T](intf: Class[T], target: AnyRef, timeout: Long, transactionRequired: Boolean, restartCallbacks: Option[RestartCallbacks]): T =
    newInstance(intf, target, actorOf(new Dispatcher(transactionRequired, restartCallbacks)), None, timeout)

  @deprecated("use newInstance(intf: Class[T], target: AnyRef, config: ActiveObjectConfiguration) instead")
  def newRemoteInstance[T](intf: Class[T], target: AnyRef, timeout: Long, hostname: String, port: Int): T =
    newInstance(intf, target, actorOf(new Dispatcher(false, None)), Some(new InetSocketAddress(hostname, port)), timeout)

  @deprecated("use newInstance(intf: Class[T], target: AnyRef, config: ActiveObjectConfiguration) instead")
  def newRemoteInstance[T](intf: Class[T], target: AnyRef, timeout: Long, hostname: String, port: Int, restartCallbacks: Option[RestartCallbacks]): T =
    newInstance(intf, target, actorOf(new Dispatcher(false, restartCallbacks)), Some(new InetSocketAddress(hostname, port)), timeout)

  @deprecated("use newInstance(target: Class[T], config: ActiveObjectConfiguration) instead")
  def newRemoteInstance[T](target: Class[T], timeout: Long, transactionRequired: Boolean, hostname: String, port: Int): T =
    newInstance(target, actorOf(new Dispatcher(transactionRequired, None)), Some(new InetSocketAddress(hostname, port)), timeout)

  @deprecated("use newInstance(target: Class[T], config: ActiveObjectConfiguration) instead")
  def newRemoteInstance[T](target: Class[T], timeout: Long, transactionRequired: Boolean, hostname: String, port: Int, restartCallbacks: Option[RestartCallbacks]): T =
    newInstance(target, actorOf(new Dispatcher(transactionRequired, restartCallbacks)), Some(new InetSocketAddress(hostname, port)), timeout)

  @deprecated("use newInstance(intf: Class[T], target: AnyRef, config: ActiveObjectConfiguration) instead")
  def newRemoteInstance[T](intf: Class[T], target: AnyRef, timeout: Long, transactionRequired: Boolean, hostname: String, port: Int): T =
    newInstance(intf, target, actorOf(new Dispatcher(transactionRequired, None)), Some(new InetSocketAddress(hostname, port)), timeout)

  @deprecated("use newInstance(intf: Class[T], target: AnyRef, config: ActiveObjectConfiguration) instead")
  def newRemoteInstance[T](intf: Class[T], target: AnyRef, timeout: Long, transactionRequired: Boolean, hostname: String, port: Int, restartCallbacks: Option[RestartCallbacks]): T =
    newInstance(intf, target, actorOf(new Dispatcher(transactionRequired, restartCallbacks)), Some(new InetSocketAddress(hostname, port)), timeout)

  @deprecated("use newInstance(target: Class[T], config: ActiveObjectConfiguration) instead")
  def newInstance[T](target: Class[T], timeout: Long, dispatcher: MessageDispatcher): T = {
    val actor = actorOf(new Dispatcher(false, None))
    actor.dispatcher = dispatcher
    newInstance(target, actor, None, timeout)
  }

  @deprecated("use newInstance(target: Class[T], config: ActiveObjectConfiguration) instead")
  def newInstance[T](target: Class[T], timeout: Long, dispatcher: MessageDispatcher, restartCallbacks: Option[RestartCallbacks]): T = {
    val actor = actorOf(new Dispatcher(false, restartCallbacks))
    actor.dispatcher = dispatcher
    newInstance(target, actor, None, timeout)
  }

  @deprecated("use newInstance(intf: Class[T], target: AnyRef, config: ActiveObjectConfiguration) instead")
  def newInstance[T](intf: Class[T], target: AnyRef, timeout: Long, dispatcher: MessageDispatcher): T = {
    val actor = actorOf(new Dispatcher(false, None))
    actor.dispatcher = dispatcher
    newInstance(intf, target, actor, None, timeout)
  }

  @deprecated("use newInstance(intf: Class[T], target: AnyRef, config: ActiveObjectConfiguration) instead")
  def newInstance[T](intf: Class[T], target: AnyRef, timeout: Long,
                     dispatcher: MessageDispatcher, restartCallbacks: Option[RestartCallbacks]): T = {
    val actor = actorOf(new Dispatcher(false, restartCallbacks))
    actor.dispatcher = dispatcher
    newInstance(intf, target, actor, None, timeout)
  }

  @deprecated("use newInstance(target: Class[T], config: ActiveObjectConfiguration) instead")
  def newInstance[T](target: Class[T], timeout: Long, transactionRequired: Boolean, dispatcher: MessageDispatcher): T = {
    val actor = actorOf(new Dispatcher(transactionRequired, None))
    actor.dispatcher = dispatcher
    newInstance(target, actor, None, timeout)
  }

  @deprecated("use newInstance(target: Class[T], config: ActiveObjectConfiguration) instead")
  def newInstance[T](target: Class[T], timeout: Long, transactionRequired: Boolean,
                     dispatcher: MessageDispatcher, restartCallbacks: Option[RestartCallbacks]): T = {
    val actor = actorOf(new Dispatcher(transactionRequired, restartCallbacks))
    actor.dispatcher = dispatcher
    newInstance(target, actor, None, timeout)
  }

  @deprecated("use newInstance(intf: Class[T], target: AnyRef, config: ActiveObjectConfiguration) instead")
  def newInstance[T](intf: Class[T], target: AnyRef, timeout: Long, transactionRequired: Boolean, dispatcher: MessageDispatcher): T = {
    val actor = actorOf(new Dispatcher(transactionRequired, None))
    actor.dispatcher = dispatcher
    newInstance(intf, target, actor, None, timeout)
  }

  @deprecated("use newInstance(intf: Class[T], target: AnyRef, config: ActiveObjectConfiguration) instead")
  def newInstance[T](intf: Class[T], target: AnyRef, timeout: Long, transactionRequired: Boolean,
                     dispatcher: MessageDispatcher, restartCallbacks: Option[RestartCallbacks]): T = {
    val actor = actorOf(new Dispatcher(transactionRequired, restartCallbacks))
    actor.dispatcher = dispatcher
    newInstance(intf, target, actor, None, timeout)
  }

  @deprecated("use newInstance(target: Class[T], config: ActiveObjectConfiguration) instead")
  def newRemoteInstance[T](target: Class[T], timeout: Long, dispatcher: MessageDispatcher, hostname: String, port: Int): T = {
    val actor = actorOf(new Dispatcher(false, None))
    actor.dispatcher = dispatcher
    newInstance(target, actor, Some(new InetSocketAddress(hostname, port)), timeout)
  }

  @deprecated("use newInstance(target: Class[T], config: ActiveObjectConfiguration) instead")
  def newRemoteInstance[T](target: Class[T], timeout: Long, dispatcher: MessageDispatcher,
                           hostname: String, port: Int, restartCallbacks: Option[RestartCallbacks]): T = {
    val actor = actorOf(new Dispatcher(false, restartCallbacks))
    actor.dispatcher = dispatcher
    newInstance(target, actor, Some(new InetSocketAddress(hostname, port)), timeout)
  }

  @deprecated("use newInstance(intf: Class[T], target: AnyRef, config: ActiveObjectConfiguration) instead")
  def newRemoteInstance[T](intf: Class[T], target: AnyRef, timeout: Long, dispatcher: MessageDispatcher, hostname: String, port: Int): T = {
    val actor = actorOf(new Dispatcher(false, None))
    actor.dispatcher = dispatcher
    newInstance(intf, target, actor, Some(new InetSocketAddress(hostname, port)), timeout)
  }

  @deprecated("use newInstance(intf: Class[T], target: AnyRef, config: ActiveObjectConfiguration) instead")
  def newRemoteInstance[T](intf: Class[T], target: AnyRef, timeout: Long, dispatcher: MessageDispatcher,
                           hostname: String, port: Int, restartCallbacks: Option[RestartCallbacks]): T = {
    val actor = actorOf(new Dispatcher(false, restartCallbacks))
    actor.dispatcher = dispatcher
    newInstance(intf, target, actor, Some(new InetSocketAddress(hostname, port)), timeout)
  }

  @deprecated("use newInstance(target: Class[T], config: ActiveObjectConfiguration) instead")
  def newRemoteInstance[T](target: Class[T], timeout: Long, transactionRequired: Boolean,
                           dispatcher: MessageDispatcher, hostname: String, port: Int): T = {
    val actor = actorOf(new Dispatcher(transactionRequired, None))
    actor.dispatcher = dispatcher
    newInstance(target, actor, Some(new InetSocketAddress(hostname, port)), timeout)
  }

  @deprecated("use newInstance(target: Class[T], config: ActiveObjectConfiguration) instead")
  def newRemoteInstance[T](target: Class[T], timeout: Long, transactionRequired: Boolean, dispatcher: MessageDispatcher,
                          hostname: String, port: Int, restartCallbacks: Option[RestartCallbacks]): T = {
    val actor = actorOf(new Dispatcher(transactionRequired, restartCallbacks))
    actor.dispatcher = dispatcher
    newInstance(target, actor, Some(new InetSocketAddress(hostname, port)), timeout)
  }

  @deprecated("use newInstance(intf: Class[T], target: AnyRef, config: ActiveObjectConfiguration) instead")
  def newRemoteInstance[T](intf: Class[T], target: AnyRef, timeout: Long, transactionRequired: Boolean,
                           dispatcher: MessageDispatcher, hostname: String, port: Int): T = {
    val actor = actorOf(new Dispatcher(transactionRequired, None))
    actor.dispatcher = dispatcher
    newInstance(intf, target, actor, Some(new InetSocketAddress(hostname, port)), timeout)
  }

  @deprecated("use newInstance(intf: Class[T], target: AnyRef, config: ActiveObjectConfiguration) instead")
  def newRemoteInstance[T](intf: Class[T], target: AnyRef, timeout: Long, transactionRequired: Boolean,
                           dispatcher: MessageDispatcher, hostname: String, port: Int, restartCallbacks: Option[RestartCallbacks]): T = {
    val actor = actorOf(new Dispatcher(transactionRequired, restartCallbacks))
    actor.dispatcher = dispatcher
    newInstance(intf, target, actor, Some(new InetSocketAddress(hostname, port)), timeout)
  }

  private[akka] def newInstance[T](target: Class[T], actorRef: ActorRef, remoteAddress: Option[InetSocketAddress], timeout: Long): T = {
    val proxy = Proxy.newInstance(target, true, false)
    val context = injectActiveObjectContext(proxy)
    actorRef.actor.asInstanceOf[Dispatcher].initialize(target, proxy, context)
    actorRef.timeout = timeout
    if (remoteAddress.isDefined) actorRef.makeRemote(remoteAddress.get)
    AspectInitRegistry.register(proxy, AspectInit(target, actorRef, remoteAddress, timeout))
    actorRef.start
    proxy.asInstanceOf[T]
  }

  private[akka] def newInstance[T](intf: Class[T], target: AnyRef, actorRef: ActorRef,
                                   remoteAddress: Option[InetSocketAddress], timeout: Long): T = {
    val context = injectActiveObjectContext(target)
    val proxy = Proxy.newInstance(Array(intf), Array(target), true, false)
    actorRef.actor.asInstanceOf[Dispatcher].initialize(target.getClass, target, context)
    actorRef.timeout = timeout
    if (remoteAddress.isDefined) actorRef.makeRemote(remoteAddress.get)
    AspectInitRegistry.register(proxy, AspectInit(intf, actorRef, remoteAddress, timeout))
    actorRef.start
    proxy.asInstanceOf[T]
  }

  def stop(obj: AnyRef): Unit = {
    val init = AspectInitRegistry.initFor(obj)
    init.actorRef.stop
  }

  /**
   * Get the underlying dispatcher actor for the given active object.
   */
  def actorFor(obj: AnyRef): Option[ActorRef] =
    ActorRegistry.actorsFor(classOf[Dispatcher]).find(a => a.actor.asInstanceOf[Dispatcher].target == Some(obj))

  /**
   * Links an other active object to this active object.
   * @param supervisor the supervisor active object
   * @param supervised the active object to link
   */
  def link(supervisor: AnyRef, supervised: AnyRef) = {
    val supervisorActor = actorFor(supervisor).getOrElse(
      throw new IllegalActorStateException("Can't link when the supervisor is not an active object"))
    val supervisedActor = actorFor(supervised).getOrElse(
      throw new IllegalActorStateException("Can't link when the supervised is not an active object"))
    supervisorActor.link(supervisedActor)
  }

  /**
   * Links an other active object to this active object and sets the fault handling for the supervisor.
   * @param supervisor the supervisor active object
   * @param supervised the active object to link
   * @param handler fault handling strategy
   * @param trapExceptions array of exceptions that should be handled by the supervisor
   */
  def link(supervisor: AnyRef, supervised: AnyRef, handler: FaultHandlingStrategy, trapExceptions: Array[Class[_ <: Throwable]]) = {
    val supervisorActor = actorFor(supervisor).getOrElse(
      throw new IllegalActorStateException("Can't link when the supervisor is not an active object"))
    val supervisedActor = actorFor(supervised).getOrElse(
      throw new IllegalActorStateException("Can't link when the supervised is not an active object"))
    supervisorActor.trapExit = trapExceptions.toList
    supervisorActor.faultHandler = Some(handler)
    supervisorActor.link(supervisedActor)
  }

  /**
   * Unlink the supervised active object from the supervisor.
   * @param supervisor the supervisor active object
   * @param supervised the active object to unlink
   */
  def unlink(supervisor: AnyRef, supervised: AnyRef) = {
    val supervisorActor = actorFor(supervisor).getOrElse(
      throw new IllegalActorStateException("Can't unlink when the supervisor is not an active object"))
    val supervisedActor = actorFor(supervised).getOrElse(
      throw new IllegalActorStateException("Can't unlink when the supervised is not an active object"))
    supervisorActor.unlink(supervisedActor)
  }

  /**
   * Sets the trap exit for the given supervisor active object.
   * @param supervisor the supervisor active object
   * @param trapExceptions array of exceptions that should be handled by the supervisor
   */
  def trapExit(supervisor: AnyRef, trapExceptions: Array[Class[_ <: Throwable]]) = {
    val supervisorActor = actorFor(supervisor).getOrElse(
      throw new IllegalActorStateException("Can't set trap exceptions when the supervisor is not an active object"))
    supervisorActor.trapExit = trapExceptions.toList
    this
  }

  /**
   * Sets the fault handling strategy for the given supervisor active object.
   * @param supervisor the supervisor active object
   * @param handler fault handling strategy
   */
  def faultHandler(supervisor: AnyRef, handler: FaultHandlingStrategy) = {
    val supervisorActor = actorFor(supervisor).getOrElse(
      throw new IllegalActorStateException("Can't set fault handler when the supervisor is not an active object"))
    supervisorActor.faultHandler = Some(handler)
    this
  }

  private def injectActiveObjectContext(activeObject: AnyRef): Option[ActiveObjectContext] = {
    def injectActiveObjectContext0(activeObject: AnyRef, clazz: Class[_]): Option[ActiveObjectContext] = {
      val contextField = clazz.getDeclaredFields.toList.find(_.getType == classOf[ActiveObjectContext])
      if (contextField.isDefined) {
        contextField.get.setAccessible(true)
        val context = new ActiveObjectContext
        contextField.get.set(activeObject, context)
        Some(context)
      } else {
        val parent = clazz.getSuperclass
        if (parent != null) injectActiveObjectContext0(activeObject, parent)
        else {
          log.ifTrace("Can't set 'ActiveObjectContext' for ActiveObject [" + 
                      activeObject.getClass.getName + 
                      "] since no field of this type could be found.")
          None
        }
      }
    }
    injectActiveObjectContext0(activeObject, activeObject.getClass)
  }

  private[akka] def supervise(restartStrategy: RestartStrategy, components: List[Supervise]): Supervisor =
    Supervisor(SupervisorConfig(restartStrategy, components))
}

private[akka] object AspectInitRegistry extends ListenerManagement {
  private val initializations = new java.util.concurrent.ConcurrentHashMap[AnyRef, AspectInit]

  def initFor(target: AnyRef) = {
    initializations.get(target)
  }

  def register(target: AnyRef, init: AspectInit) = {
    val res = initializations.put(target, init)
    foreachListener(_ ! AspectInitRegistered(target, init))
    res
  }

  def unregister(target: AnyRef) = {
    val res = initializations.remove(target)
    foreachListener(_ ! AspectInitUnregistered(target, res))
    res
  }
}

private[akka] sealed trait AspectInitRegistryEvent
private[akka] case class AspectInitRegistered(proxy: AnyRef, init: AspectInit) extends AspectInitRegistryEvent
private[akka] case class AspectInitUnregistered(proxy: AnyRef, init: AspectInit) extends AspectInitRegistryEvent

private[akka] sealed case class AspectInit(
  val target: Class[_],
  val actorRef: ActorRef,
  val remoteAddress: Option[InetSocketAddress],
  val timeout: Long) {
  def this(target: Class[_], actorRef: ActorRef, timeout: Long) = this(target, actorRef, None, timeout)
}

/**
 * AspectWerkz Aspect that is turning POJOs into Active Object.
 * Is deployed on a 'per-instance' basis.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@Aspect("perInstance")
private[akka] sealed class ActiveObjectAspect {
  @volatile private var isInitialized = false
  @volatile private var isStopped = false
  private var target: Class[_] = _
  private var actorRef: ActorRef = _
  private var remoteAddress: Option[InetSocketAddress] = _
  private var timeout: Long = _
  @volatile private var instance: AnyRef = _

  @Around("execution(* *.*(..))")
  def invoke(joinPoint: JoinPoint): AnyRef = {
    if (!isInitialized) {
      val init = AspectInitRegistry.initFor(joinPoint.getThis)
      target = init.target
      actorRef = init.actorRef
      remoteAddress = init.remoteAddress
      timeout = init.timeout
      isInitialized = true

    }
    dispatch(joinPoint)
  }

  private def dispatch(joinPoint: JoinPoint) = {
    if (remoteAddress.isDefined) remoteDispatch(joinPoint)
    else localDispatch(joinPoint)
  }

  private def localDispatch(joinPoint: JoinPoint): AnyRef = {
    val rtti = joinPoint.getRtti.asInstanceOf[MethodRtti]
    val isOneWay = isVoid(rtti)
    val sender = ActiveObjectContext.sender.value
    val senderFuture = ActiveObjectContext.senderFuture.value

    if (!actorRef.isRunning && !isStopped) {
      isStopped = true
      joinPoint.proceed
    } else if (isOneWay) {
      actorRef ! Invocation(joinPoint, true, true, sender, senderFuture)
      null.asInstanceOf[AnyRef]
    } else {
      val result = (actorRef !! (Invocation(joinPoint, false, isOneWay, sender, senderFuture), timeout)).as[AnyRef]
      if (result.isDefined) result.get
      else throw new IllegalActorStateException("No result defined for invocation [" + joinPoint + "]")
    }
  }

  private def remoteDispatch(joinPoint: JoinPoint): AnyRef = {
    val rtti = joinPoint.getRtti.asInstanceOf[MethodRtti]
    val isOneWay = isVoid(rtti)
    val (message: Array[AnyRef], isEscaped) = escapeArguments(rtti.getParameterValues)
    val requestBuilder = RemoteRequestProtocol.newBuilder
      .setId(RemoteRequestProtocolIdFactory.nextId)
      .setMessage(MessageSerializer.serialize(message))
      .setMethod(rtti.getMethod.getName)
      .setTarget(target.getName)
      .setUuid(actorRef.uuid)
      .setTimeout(timeout)
      .setIsActor(false)
      .setIsOneWay(isOneWay)
      .setIsEscaped(false)
    val id = actorRef.registerSupervisorAsRemoteActor
    if (id.isDefined) requestBuilder.setSupervisorUuid(id.get)
    val remoteMessage = requestBuilder.build
    val future = RemoteClient.clientFor(remoteAddress.get).send(remoteMessage, None)
    if (isOneWay) null // for void methods
    else {
      if (future.isDefined) {
        future.get.await
        val result = getResultOrThrowException(future.get)
        if (result.isDefined) result.get
        else throw new IllegalActorStateException("No result returned from call to [" + joinPoint + "]")
      } else throw new IllegalActorStateException("No future returned from call to [" + joinPoint + "]")
    }
  }

  private def getResultOrThrowException[T](future: Future[T]): Option[T] =
    if (future.exception.isDefined) {
      val (_, cause) = future.exception.get
      throw cause
    } else future.result

  private def isVoid(rtti: MethodRtti) = rtti.getMethod.getReturnType == java.lang.Void.TYPE

  private def escapeArguments(args: Array[AnyRef]): Tuple2[Array[AnyRef], Boolean] = {
    var isEscaped = false
    val escapedArgs = for (arg <- args) yield {
      val clazz = arg.getClass
      if (clazz.getName.contains(ActiveObject.AW_PROXY_PREFIX)) {
        isEscaped = true
        ActiveObject.AW_PROXY_PREFIX + clazz.getSuperclass.getName
      } else arg
    }
    (escapedArgs, isEscaped)
  }
}

/**
 * Represents a snapshot of the current invocation.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@serializable private[akka] case class Invocation(
  joinPoint: JoinPoint, isOneWay: Boolean, isVoid: Boolean, sender: AnyRef, senderFuture: CompletableFuture[Any]) {

  override def toString: String = synchronized {
    "Invocation [" +
    "\n\t\tmethod = " + joinPoint.getRtti.asInstanceOf[MethodRtti].getMethod.getName + " @ " + joinPoint.getTarget.getClass.getName +
    "\n\t\tisOneWay = " + isOneWay +
    "\n\t\tisVoid = " + isVoid +
    "\n\t\tsender = " + sender +
    "\n\t\tsenderFuture = " + senderFuture +
    "]"
  }

  override def hashCode: Int = synchronized {
    var result = HashCode.SEED
    result = HashCode.hash(result, joinPoint)
    result = HashCode.hash(result, isOneWay)
    result = HashCode.hash(result, isVoid)
    result = HashCode.hash(result, sender)
    result = HashCode.hash(result, senderFuture)
    result
  }

  override def equals(that: Any): Boolean = synchronized {
    that != null &&
    that.isInstanceOf[Invocation] &&
    that.asInstanceOf[Invocation].joinPoint == joinPoint &&
    that.asInstanceOf[Invocation].isOneWay == isOneWay &&
    that.asInstanceOf[Invocation].isVoid == isVoid &&
    that.asInstanceOf[Invocation].sender == sender &&
    that.asInstanceOf[Invocation].senderFuture == senderFuture
  }
}

object Dispatcher {
  val ZERO_ITEM_CLASS_ARRAY = Array[Class[_]]()
  val ZERO_ITEM_OBJECT_ARRAY = Array[Object]()
  var crashedActorTl:ThreadLocal[Dispatcher] = new ThreadLocal();
}

/**
 * Generic Actor managing Invocation dispatch, transaction and error management.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
private[akka] class Dispatcher(transactionalRequired: Boolean,
                               var restartCallbacks: Option[RestartCallbacks] = None,
                               var shutdownCallback: Option[ShutdownCallback] = None) extends Actor {
  import Dispatcher._

  private[actor] var target: Option[AnyRef] = None
  private var zhutdown: Option[Method] = None
  private var preRestart: Option[Method] = None
  private var postRestart: Option[Method] = None
  private var initTxState: Option[Method] = None
  private var context: Option[ActiveObjectContext] = None
  private var targetClass:Class[_] = _

  def this(transactionalRequired: Boolean) = this(transactionalRequired,None)

  private[actor] def initialize(targetClass: Class[_], targetInstance: AnyRef, ctx: Option[ActiveObjectContext]) = {

   if (transactionalRequired || targetClass.isAnnotationPresent(Annotations.transactionrequired))
      self.makeTransactionRequired
    self.id = targetClass.getName
    this.targetClass = targetClass
    target = Some(targetInstance)
    context = ctx
    val methods = targetInstance.getClass.getDeclaredMethods.toList

    if (self.lifeCycle.isEmpty) self.lifeCycle = Some(LifeCycle(Permanent))
    
    // See if we have any config define restart callbacks
    restartCallbacks match {
      case None => {}
      case Some(RestartCallbacks(pre, post)) =>
        preRestart = Some(try {
          targetInstance.getClass.getDeclaredMethod(pre, ZERO_ITEM_CLASS_ARRAY: _*)
        } catch { case e => throw new IllegalActorStateException(
          "Could not find pre restart method [" + pre + "] \nin [" +
          targetClass.getName + "]. \nIt must have a zero argument definition.") })
        postRestart = Some(try {
          targetInstance.getClass.getDeclaredMethod(post, ZERO_ITEM_CLASS_ARRAY: _*)
        } catch { case e => throw new IllegalActorStateException(
          "Could not find post restart method [" + post + "] \nin [" +
          targetClass.getName + "]. \nIt must have a zero argument definition.") })
    }
    // See if we have any config define a shutdown callback
    shutdownCallback match {
      case None => {}
      case Some(ShutdownCallback(down)) =>
        zhutdown = Some(try {
          targetInstance.getClass.getDeclaredMethod(down, ZERO_ITEM_CLASS_ARRAY: _*)
        } catch { case e => throw new IllegalStateException(
          "Could not find shutdown method [" + down + "] \nin [" +
          targetClass.getName + "]. \nIt must have a zero argument definition.") })
    }

    // See if we have any annotation defined restart callbacks
    if (!preRestart.isDefined) preRestart = methods.find(m => m.isAnnotationPresent(Annotations.prerestart))
    if (!postRestart.isDefined) postRestart = methods.find(m => m.isAnnotationPresent(Annotations.postrestart))
    // See if we have an annotation defined shutdown callback
    if (!zhutdown.isDefined) zhutdown = methods.find(m => m.isAnnotationPresent(Annotations.shutdown))

    if (preRestart.isDefined && preRestart.get.getParameterTypes.length != 0)
      throw new IllegalActorStateException(
        "Method annotated with @prerestart or defined as a restart callback in \n[" +
        targetClass.getName + "] must have a zero argument definition")
    if (postRestart.isDefined && postRestart.get.getParameterTypes.length != 0)
      throw new IllegalActorStateException(
        "Method annotated with @postrestart or defined as a restart callback in \n[" +
        targetClass.getName + "] must have a zero argument definition")
    if (zhutdown.isDefined && zhutdown.get.getParameterTypes.length != 0)
      throw new IllegalStateException(
        "Method annotated with @shutdown or defined as a shutdown callback in \n[" +
        targetClass.getName + "] must have a zero argument definition")

    if (preRestart.isDefined) preRestart.get.setAccessible(true)
    if (postRestart.isDefined) postRestart.get.setAccessible(true)
    if (zhutdown.isDefined) zhutdown.get.setAccessible(true)

    // see if we have a method annotated with @inittransactionalstate, if so invoke it
    initTxState = methods.find(m => m.isAnnotationPresent(Annotations.inittransactionalstate))
    if (initTxState.isDefined && initTxState.get.getParameterTypes.length != 0)
      throw new IllegalActorStateException("Method annotated with @inittransactionalstate must have a zero argument definition")
    if (initTxState.isDefined) initTxState.get.setAccessible(true)
  }

  def receive = {
    case invocation @ Invocation(joinPoint, isOneWay, _, sender, senderFuture) =>
      ActiveObject.log.ifTrace("Invoking active object with message:\n" + invocation)
      context.foreach { ctx =>
        if (sender ne null) ctx._sender = sender
        if (senderFuture ne null) ctx._senderFuture = senderFuture
      }
      ActiveObjectContext.sender.value = joinPoint.getThis // set next sender
      self.senderFuture.foreach(ActiveObjectContext.senderFuture.value = _)
      if (Actor.SERIALIZE_MESSAGES) serializeArguments(joinPoint)
      if (isOneWay) joinPoint.proceed
      else self.reply(joinPoint.proceed)

    // Jan Kronquist: started work on issue 121
    case Link(target)   => self.link(target)
    case Unlink(target) => self.unlink(target)
    case unexpected     => throw new IllegalActorStateException(
      "Unexpected message [" + unexpected + "] sent to [" + this + "]")
  }

  override def preRestart(reason: Throwable) {
    try {
       // Since preRestart is called we know that this dispatcher
       // is about to be restarted. Put the instance in a thread
       // local so the new dispatcher can be initialized with the 
       // contents of the old.
       //FIXME - This should be considered as a workaround.
       crashedActorTl.set(this)
       preRestart.foreach(_.invoke(target.get, ZERO_ITEM_OBJECT_ARRAY: _*))
    } catch { case e: InvocationTargetException => throw e.getCause }
  }

  override def postRestart(reason: Throwable) {
    try {
      postRestart.foreach(_.invoke(target.get, ZERO_ITEM_OBJECT_ARRAY: _*))
    } catch { case e: InvocationTargetException => throw e.getCause }
  }

  override def init = {
    // Get the crashed dispatcher from thread local and intitialize this actor with the
    // contents of the old dispatcher
    val oldActor = crashedActorTl.get();
    if (oldActor != null) {
      initialize(oldActor.targetClass, oldActor.target.get, oldActor.context)
      crashedActorTl.set(null)
    }
  }

  override def shutdown = {
    try {
      zhutdown.foreach(_.invoke(target.get, ZERO_ITEM_OBJECT_ARRAY: _*))
    } catch { case e: InvocationTargetException => throw e.getCause
    } finally { 
      AspectInitRegistry.unregister(target.get);
    }
  }

  override def initTransactionalState = {
    try {
      if (initTxState.isDefined && target.isDefined) initTxState.get.invoke(target.get, ZERO_ITEM_OBJECT_ARRAY: _*)
    } catch { case e: InvocationTargetException => throw e.getCause }
  }

  private def serializeArguments(joinPoint: JoinPoint) = {
    val args = joinPoint.getRtti.asInstanceOf[MethodRtti].getParameterValues
    var unserializable = false
    var hasMutableArgument = false
    for (arg <- args.toList) {
      if (!arg.isInstanceOf[String] &&
        !arg.isInstanceOf[Byte] &&
        !arg.isInstanceOf[Int] &&
        !arg.isInstanceOf[Long] &&
        !arg.isInstanceOf[Float] &&
        !arg.isInstanceOf[Double] &&
        !arg.isInstanceOf[Boolean] &&
        !arg.isInstanceOf[Char] &&
        !arg.isInstanceOf[java.lang.Byte] &&
        !arg.isInstanceOf[java.lang.Integer] &&
        !arg.isInstanceOf[java.lang.Long] &&
        !arg.isInstanceOf[java.lang.Float] &&
        !arg.isInstanceOf[java.lang.Double] &&
        !arg.isInstanceOf[java.lang.Boolean] &&
        !arg.isInstanceOf[java.lang.Character]) {
        hasMutableArgument = true
      }
      if (arg.getClass.getName.contains(ActiveObject.AW_PROXY_PREFIX)) unserializable = true
    }
    if (!unserializable && hasMutableArgument) {
      val copyOfArgs = Serializer.Java.deepClone(args)
      joinPoint.getRtti.asInstanceOf[MethodRtti].setParameterValues(copyOfArgs.asInstanceOf[Array[AnyRef]])
    }
  }
}
