/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.actor

import Actor._
import se.scalablesolutions.akka.config.FaultHandlingStrategy
import se.scalablesolutions.akka.remote.protocol.RemoteProtocol._
import se.scalablesolutions.akka.remote.{MessageSerializer, RemoteClient, RemoteRequestProtocolIdFactory}
import se.scalablesolutions.akka.dispatch.{MessageDispatcher, Future, CompletableFuture}
import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.serialization.Serializer
import se.scalablesolutions.akka.util._
import se.scalablesolutions.akka.actor.annotation._

import org.codehaus.aspectwerkz.joinpoint.{MethodRtti, JoinPoint}
import org.codehaus.aspectwerkz.proxy.Proxy
import org.codehaus.aspectwerkz.annotation.{Aspect, Around}

import java.net.InetSocketAddress
import java.lang.reflect.{InvocationTargetException, Method}

import scala.reflect.BeanProperty
 
/**
 * FIXME: document TypedActor
 *
 * Here is an example of usage (in Java):
 * <pre>
 * class PingImpl extends TypedActor implements Ping {
 *   public void hit(int count) {
 *     Pong pong = (Pong) getContext().getSender();
 *     pong.hit(count++);
 *   }
 * 
 *   @Override
 *   public void init() {
 *     ... // optional initialization on start
 *   }
 * 
 *   @Override
 *   public void shutdown() {
 *     ... // optional cleanup on stop
 *   }
 *
 *   ... // more life-cycle callbacks if needed
 * }
 * 
 * // create the ping actor
 * Ping ping = TypedActor.newInstance(Ping.class, PingImpl.class);
 *
 * ping.hit(1); // use the actor
 * ping.hit(1);
 *
 * // stop the actor
 * TypedActor.stop(ping);
 * </pre>
 * 
 * Here is an example of usage (in Scala):
 * <pre>
 * class PingImpl extends TypedActor with Ping {
 *   def hit(count: Int) = {
 *     val pong = context.sender.asInstanceOf[Pong]
 *     pong.hit(count += 1)
 *   }
 *
 *   override def init = {
 *     ... // optional initialization on start
 *   }
 * 
 *   override def shutdown = {
 *     ... // optional cleanup on stop
 *   }
 *
 *   ... // more life-cycle callbacks if needed
 * }
 * 
 * // create the ping actor
 * val ping = TypedActor.newInstance(classOf[Ping], classOf[PingImpl])
 *
 * ping.hit(1) // use the actor
 * ping.hit(1)
 *
 * // stop the actor
 * TypedActor.stop(ping)
 * </pre>
 * 
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
abstract class TypedActor extends Logging {

  /**
   * Holds RTTI (runtime type information) for the TypedActor, f.e. current 'sender'
   * reference, the 'senderFuture' reference etc.
   * <p/>
   * This class does not contain static information but is updated by the runtime system
   * at runtime.
   * <p/>
   * You can get a hold of the context using either the 'getContext()' or 'context' 
   * methods from the 'TypedActor' base class.
   * <p/>
   *
   * Here is an example of usage (in Java):
   * <pre>
   * class PingImpl extends TypedActor implements Ping {
   *   public void hit(int count) {
   *     Pong pong = (Pong) getContext().getSender();
   *     pong.hit(count++);
   *   }
   * }
   * </pre>
   * 
   * Here is an example of usage (in Scala):
   * <pre>
   * class PingImpl extends TypedActor with Ping {
   *   def hit(count: Int) = {
   *     val pong = context.sender.asInstanceOf[Pong]
   *     pong.hit(count += 1)
   *   }
   * }
   * </pre>
   */
  @BeanProperty protected var context: TypedActorContext = _

  /**
   * The uuid for the Typed Actor.
   */
  @BeanProperty @volatile var uuid = UUID.newUuid.toString
  
  /**
   * Identifier for actor, does not have to be a unique one. Default is the 'uuid'.
   * <p/>
   * This field is used for logging, AspectRegistry.actorsFor(id), identifier for remote
   * actor in RemoteServer etc.But also as the identifier for persistence, which means
   * that you can use a custom name to be able to retrieve the "correct" persisted state
   * upon restart, remote restart etc.
   * <p/>
   * This property can be set to a custom ID.
   */
  @BeanProperty @volatile protected var id: String = uuid

  /**
   * Defines the default timeout for '!!' and '!!!' invocations,
   * e.g. the timeout for the future returned by the call to '!!' and '!!!'.
   * <p/>
   * This property can be set to a custom timeout.
   */
  @BeanProperty @volatile protected var timeout: Long = Actor.TIMEOUT

  /**
   * User overridable callback.
   * <p/>
   * Is called when an Actor is started by invoking 'actor.start'.
   */
  def init {}

  /**
   * User overridable callback.
   * <p/>
   * Is called when 'actor.stop' is invoked.
   */
  def shutdown {}

  /**
   * User overridable callback.
   * <p/>
   * Is called on a crashed Actor right BEFORE it is restarted to allow clean up of resources before Actor is terminated.
   */
  def preRestart(reason: Throwable) {}

  /**
   * User overridable callback.
   * <p/>
   * Is called right AFTER restart on the newly created Actor to allow reinitialization after an Actor crash.
   */
  def postRestart(reason: Throwable) {}

  /**
   * User overridable callback.
   * <p/>
   * Is called during initialization. Can be used to initialize transactional state. Will be invoked within a transaction.
   */
  def initTransactionalState {}
}

/**
 * FIXME: document TypedTransactor
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@transactionrequired 
abstract class TypedTransactor extends TypedActor

/**
 * Configuration factory for TypedActors.
 *
 * FIXDOC: document TypedActorConfiguration
 * 
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
final class TypedActorConfiguration {
  private[akka] var _timeout: Long = Actor.TIMEOUT
  private[akka] var _restartCallbacks: Option[RestartCallbacks] = None
  private[akka] var _shutdownCallback: Option[ShutdownCallback] = None
  private[akka] var _transactionRequired = false
  private[akka] var _host: Option[InetSocketAddress] = None
  private[akka] var _messageDispatcher: Option[MessageDispatcher] = None

  def timeout = _timeout
  def timeout(timeout: Duration) : TypedActorConfiguration = {
    _timeout = timeout.toMillis
    this
  }

  def restartCallbacks(pre: String, post: String) : TypedActorConfiguration = {
    _restartCallbacks = Some(new RestartCallbacks(pre, post))
    this
  }

  def shutdownCallback(down: String) : TypedActorConfiguration = {
    _shutdownCallback = Some(new ShutdownCallback(down))
    this
  }

  def makeTransactionRequired() : TypedActorConfiguration = {
    _transactionRequired = true;
    this
  }

  def makeRemote(hostname: String, port: Int) : TypedActorConfiguration = {
    _host = Some(new InetSocketAddress(hostname, port))
    this
  }

  def dispatcher(messageDispatcher: MessageDispatcher) : TypedActorConfiguration = {
    _messageDispatcher = Some(messageDispatcher)
    this
  }
}

/**
 * Holds RTTI (runtime type information) for the TypedActor, f.e. current 'sender'
 * reference, the 'senderFuture' reference etc.
 * <p/>
 * This class does not contain static information but is updated by the runtime system
 * at runtime.
 * <p/>
 * You can get a hold of the context using either the 'getContext()' or 'context' 
 * methods from the 'TypedActor' base class.
 * <p/>
 * Here is an example of usage (from Java):
 * <pre>
 * class PingImpl extends TypedActor implements Ping {
 *   public void hit(int count) {
 *     Pong pong = (Pong) getContext().getSender();
 *     pong.hit(count++);
 *   }
 * }
 * </pre>
 * 
 * Here is an example of usage (in Scala):
 * <pre>
 * class PingImpl extends TypedActor with Ping {
 *   def hit(count: Int) = {
 *     val pong = context.sender.asInstanceOf[Pong]
 *     pong.hit(count += 1)
 *   }
 * }
 * </pre>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
final class TypedActorContext {
  private[akka] var _self: AnyRef = _
  private[akka] var _sender: AnyRef = _
  private[akka] var _senderFuture: CompletableFuture[Any] = _

  /**
   * Returns the current sender reference.
   * Scala style getter.
   */
  def sender: AnyRef = {
    if (_sender eq null) throw new IllegalActorStateException("Sender reference should not be null.")
    else _sender
  }

  /**
   * Returns the current sender reference.
   * Java style getter.
   */
   def getSender: AnyRef = {
     if (_sender eq null) throw new IllegalActorStateException("Sender reference should not be null.")
     else _sender
   }

  /**
   * Returns the current sender future TypedActor reference.
   * Scala style getter.
   */
  def senderFuture: Option[CompletableFuture[Any]] = if (_senderFuture eq null) None else Some(_senderFuture)

  /**
   * Returns the current sender future TypedActor reference.
   * Java style getter.
   * This method returns 'null' if the sender future is not available.
   */
  def getSenderFuture = _senderFuture
}

/**
 * Factory class for creating TypedActors out of plain POJOs and/or POJOs with interfaces.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object TypedActor extends Logging {
  import Actor.actorOf

  val AKKA_CAMEL_ROUTING_SCHEME = "akka".intern
  private[actor] val AW_PROXY_PREFIX = "$$ProxiedByAW".intern

  def newInstance[T](intfClass: Class[T], targetClass: Class[_], timeout: Long): T = {
    newInstance(intfClass, newTypedActor(targetClass), actorOf(new Dispatcher(false)), None, timeout)    
  }

  def newInstance[T](intfClass: Class[T], targetClass: Class[_]): T = {
    newInstance(intfClass, newTypedActor(targetClass), actorOf(new Dispatcher(false)), None, Actor.TIMEOUT)    
  }

  def newRemoteInstance[T](intfClass: Class[T], targetClass: Class[_], timeout: Long, hostname: String, port: Int): T = {
    newInstance(intfClass, newTypedActor(targetClass), actorOf(new Dispatcher(false)), Some(new InetSocketAddress(hostname, port)), timeout)    
  }

  def newRemoteInstance[T](intfClass: Class[T], targetClass: Class[_], hostname: String, port: Int): T = {
    newInstance(intfClass, newTypedActor(targetClass), actorOf(new Dispatcher(false)), Some(new InetSocketAddress(hostname, port)), Actor.TIMEOUT)    
  }

  def newInstance[T](intfClass: Class[T], targetClass: Class[_], config: TypedActorConfiguration): T = {
    val actor = actorOf(new Dispatcher(config._transactionRequired))
    if (config._messageDispatcher.isDefined) actor.dispatcher = config._messageDispatcher.get
    newInstance(intfClass, newTypedActor(targetClass), actor, config._host, config.timeout)
  }

  private[akka] def newInstance[T](intfClass: Class[T], targetInstance: TypedActor, actorRef: ActorRef,
                                   remoteAddress: Option[InetSocketAddress], timeout: Long): T = {
    val context = injectTypedActorContext(targetInstance)
    val proxy = Proxy.newInstance(Array(intfClass), Array(targetInstance), true, false)
    actorRef.actor.asInstanceOf[Dispatcher].initialize(targetInstance.getClass, targetInstance, proxy, context)
    actorRef.timeout = timeout
    if (remoteAddress.isDefined) actorRef.makeRemote(remoteAddress.get)
    AspectInitRegistry.register(proxy, AspectInit(intfClass, targetInstance, actorRef, remoteAddress, timeout))
    actorRef.start
    proxy.asInstanceOf[T]
  }

  // NOTE: currently not used - but keep it around
  private[akka] def newInstance[T <: TypedActor](
      targetClass: Class[T], actorRef: ActorRef, remoteAddress: Option[InetSocketAddress], timeout: Long): T = {
    val proxy = { 
      val instance = Proxy.newInstance(targetClass, true, false)
      if (instance.isInstanceOf[TypedActor]) instance.asInstanceOf[TypedActor]
      else throw new IllegalActorStateException("Actor [" + targetClass.getName + "] is not a sub class of 'TypedActor'")
    }
    val context = injectTypedActorContext(proxy)
    actorRef.actor.asInstanceOf[Dispatcher].initialize(targetClass, proxy, proxy, context)
    actorRef.timeout = timeout
    if (remoteAddress.isDefined) actorRef.makeRemote(remoteAddress.get)
    AspectInitRegistry.register(proxy, AspectInit(targetClass, proxy, actorRef, remoteAddress, timeout))
    actorRef.start
    proxy.asInstanceOf[T]
  }

  /**
   * Stops the current Typed Actor.
   */
  def stop(proxy: AnyRef): Unit = AspectInitRegistry.initFor(proxy).actorRef.stop

  /**
   * Get the underlying dispatcher actor for the given Typed Actor.
   */
  def actorFor(proxy: AnyRef): Option[ActorRef] = 
    ActorRegistry.actorsFor(classOf[Dispatcher]).find(a => a.actor.asInstanceOf[Dispatcher].proxy == proxy)

  /**
   * Links an other Typed Actor to this Typed Actor.
   * @param supervisor the supervisor Typed Actor
   * @param supervised the Typed Actor to link
   */
  def link(supervisor: AnyRef, supervised: AnyRef) = {
    val supervisorActor = actorFor(supervisor).getOrElse(
      throw new IllegalActorStateException("Can't link when the supervisor is not an Typed Actor"))
    val supervisedActor = actorFor(supervised).getOrElse(
      throw new IllegalActorStateException("Can't link when the supervised is not an Typed Actor"))
    supervisorActor.link(supervisedActor)
  }

  /**
   * Links an other Typed Actor to this Typed Actor and sets the fault handling for the supervisor.
   * @param supervisor the supervisor Typed Actor
   * @param supervised the Typed Actor to link
   * @param handler fault handling strategy
   * @param trapExceptions array of exceptions that should be handled by the supervisor
   */
  def link(supervisor: AnyRef, supervised: AnyRef, 
           handler: FaultHandlingStrategy, trapExceptions: Array[Class[_ <: Throwable]]) = {
    val supervisorActor = actorFor(supervisor).getOrElse(
      throw new IllegalActorStateException("Can't link when the supervisor is not an Typed Actor"))
    val supervisedActor = actorFor(supervised).getOrElse(
      throw new IllegalActorStateException("Can't link when the supervised is not an Typed Actor"))
    supervisorActor.trapExit = trapExceptions.toList
    supervisorActor.faultHandler = Some(handler)
    supervisorActor.link(supervisedActor)
  }

  /**
   * Unlink the supervised Typed Actor from the supervisor.
   * @param supervisor the supervisor Typed Actor
   * @param supervised the Typed Actor to unlink
   */
  def unlink(supervisor: AnyRef, supervised: AnyRef) = {
    val supervisorActor = actorFor(supervisor).getOrElse(
      throw new IllegalActorStateException("Can't unlink when the supervisor is not an Typed Actor"))
    val supervisedActor = actorFor(supervised).getOrElse(
      throw new IllegalActorStateException("Can't unlink when the supervised is not an Typed Actor"))
    supervisorActor.unlink(supervisedActor)
  }

  /**
   * Sets the trap exit for the given supervisor Typed Actor.
   * @param supervisor the supervisor Typed Actor
   * @param trapExceptions array of exceptions that should be handled by the supervisor
   */
  def trapExit(supervisor: AnyRef, trapExceptions: Array[Class[_ <: Throwable]]) = {
    val supervisorActor = actorFor(supervisor).getOrElse(
      throw new IllegalActorStateException("Can't set trap exceptions when the supervisor is not an Typed Actor"))
    supervisorActor.trapExit = trapExceptions.toList
    this
  }

  /**
   * Sets the fault handling strategy for the given supervisor Typed Actor.
   * @param supervisor the supervisor Typed Actor
   * @param handler fault handling strategy
   */
  def faultHandler(supervisor: AnyRef, handler: FaultHandlingStrategy) = {
    val supervisorActor = actorFor(supervisor).getOrElse(
      throw new IllegalActorStateException("Can't set fault handler when the supervisor is not an Typed Actor"))
    supervisorActor.faultHandler = Some(handler)
    this
  }

  private def injectTypedActorContext(activeObject: AnyRef): Option[TypedActorContext] = {
    def injectTypedActorContext0(activeObject: AnyRef, clazz: Class[_]): Option[TypedActorContext] = {
      val contextField = clazz.getDeclaredFields.toList.find(_.getType == classOf[TypedActorContext])
      if (contextField.isDefined) {
        contextField.get.setAccessible(true)
        val context = new TypedActorContext
        contextField.get.set(activeObject, context)
        Some(context)
      } else {
        val parent = clazz.getSuperclass
        if (parent != null) injectTypedActorContext0(activeObject, parent)
        else {
          log.ifTrace("Can't set 'TypedActorContext' for TypedActor [" + 
                      activeObject.getClass.getName + 
                      "] since no field of this type could be found.")
          None
        }
      }
    }
    injectTypedActorContext0(activeObject, activeObject.getClass)
  }

  private[akka] def newTypedActor(targetClass: Class[_]): TypedActor = {
    val instance = targetClass.newInstance
    val typedActor = 
      if (instance.isInstanceOf[TypedActor]) instance.asInstanceOf[TypedActor]
      else throw new IllegalArgumentException("Actor [" + targetClass.getName + "] is not a sub class of 'TypedActor'")
    typedActor.init
    import se.scalablesolutions.akka.stm.local.atomic
    atomic {
      typedActor.initTransactionalState
    }
    typedActor
  }

  private[akka] def supervise(restartStrategy: RestartStrategy, components: List[Supervise]): Supervisor =
    Supervisor(SupervisorConfig(restartStrategy, components))
}

/**
 * Internal helper class to help pass the contextual information between threads.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
private[akka] object TypedActorContext {
  import scala.util.DynamicVariable
  private[actor] val sender =       new DynamicVariable[AnyRef](null)
  private[actor] val senderFuture = new DynamicVariable[CompletableFuture[Any]](null)
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Annotations {
  val transactionrequired =    classOf[transactionrequired]
  val prerestart =             classOf[prerestart]
  val postrestart =            classOf[postrestart]
  val shutdown =               classOf[shutdown]
  val inittransactionalstate = classOf[inittransactionalstate]
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
private[akka] object AspectInitRegistry extends ListenerManagement {
  private val initializations = new java.util.concurrent.ConcurrentHashMap[AnyRef, AspectInit]

  def initFor(proxy: AnyRef) = initializations.get(proxy)

  def register(proxy: AnyRef, init: AspectInit) = {
    val res = initializations.put(proxy, init)
    foreachListener(_ ! AspectInitRegistered(proxy, init))
    res
  }

  def unregister(proxy: AnyRef) = {
    val res = initializations.remove(proxy)
    foreachListener(_ ! AspectInitUnregistered(proxy, res))
    res
  }
}

private[akka] sealed trait AspectInitRegistryEvent
private[akka] case class AspectInitRegistered(proxy: AnyRef, init: AspectInit) extends AspectInitRegistryEvent
private[akka] case class AspectInitUnregistered(proxy: AnyRef, init: AspectInit) extends AspectInitRegistryEvent

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
private[akka] sealed case class AspectInit(
  val interfaceClass: Class[_],
  val targetInstance: TypedActor,
  val actorRef: ActorRef,
  val remoteAddress: Option[InetSocketAddress],
  val timeout: Long) {
  def this(interfaceClass: Class[_], targetInstance: TypedActor, actorRef: ActorRef, timeout: Long) = 
    this(interfaceClass, targetInstance, actorRef, None, timeout)
}

/**
 * AspectWerkz Aspect that is turning POJO into TypedActor.
 * <p/>
 * Is deployed on a 'perInstance' basis with the pointcut 'execution(* *.*(..))', 
 * e.g. all methods on the instance.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@Aspect("perInstance")
private[akka] sealed class TypedActorAspect {
  @volatile private var isInitialized = false
  @volatile private var isStopped = false
  private var interfaceClass: Class[_] = _
  private var targetInstance: TypedActor = _
  private var actorRef: ActorRef = _
  private var remoteAddress: Option[InetSocketAddress] = _
  private var timeout: Long = _
  private var uuid: String = _
  @volatile private var instance: TypedActor = _

  @Around("execution(* *.*(..))")
  def invoke(joinPoint: JoinPoint): AnyRef = {
    if (!isInitialized) {
      val init = AspectInitRegistry.initFor(joinPoint.getThis)
      interfaceClass = init.interfaceClass
      targetInstance = init.targetInstance
      uuid = targetInstance.uuid
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
    val sender = TypedActorContext.sender.value
    val senderFuture = TypedActorContext.senderFuture.value

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

    val typedActorInfo = TypedActorInfoProtocol.newBuilder
        .setInterface(interfaceClass.getName)
        .setMethod(rtti.getMethod.getName)
        .build
    
    val actorInfo = ActorInfoProtocol.newBuilder
        .setUuid(uuid)
        .setTarget(targetInstance.getClass.getName)
        .setTimeout(timeout)
        .setActorType(ActorType.TYPED_ACTOR)
        .setTypedActorInfo(typedActorInfo)
        .build

    val requestBuilder = RemoteRequestProtocol.newBuilder
      .setId(RemoteRequestProtocolIdFactory.nextId)
      .setMessage(MessageSerializer.serialize(message))
      .setActorInfo(actorInfo)
      .setIsOneWay(isOneWay)

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
      if (clazz.getName.contains(TypedActor.AW_PROXY_PREFIX)) {
        isEscaped = true
        TypedActor.AW_PROXY_PREFIX + clazz.getSuperclass.getName
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
private[akka] class Dispatcher(transactionalRequired: Boolean) extends Actor {
  import Dispatcher._

  private[actor] var proxy: AnyRef = _
  private var context: Option[TypedActorContext] = None
  private var targetClass: Class[_] = _
  @volatile private[akka] var targetInstance: TypedActor = _

  private[actor] def initialize(
    targetClass: Class[_], targetInstance: TypedActor, proxy: AnyRef, ctx: Option[TypedActorContext]) = {
   if (transactionalRequired || isTransactional(targetClass)) self.makeTransactionRequired
   
    self.id = targetClass.getName
    this.targetClass = targetClass
    this.proxy = proxy
    this.targetInstance = targetInstance
    this.context = ctx

    if (self.lifeCycle.isEmpty) self.lifeCycle = Some(LifeCycle(Permanent))
  }

  def receive = {
    case invocation @ Invocation(joinPoint, isOneWay, _, sender, senderFuture) =>
      TypedActor.log.ifTrace("Invoking Typed Actor with message:\n" + invocation)
      context.foreach { ctx =>
        if (sender ne null) ctx._sender = sender
        if (senderFuture ne null) ctx._senderFuture = senderFuture
      }
      TypedActorContext.sender.value = joinPoint.getThis // set next sender
      self.senderFuture.foreach(TypedActorContext.senderFuture.value = _)
      if (Actor.SERIALIZE_MESSAGES) serializeArguments(joinPoint)
      if (isOneWay) joinPoint.proceed
      else self.reply(joinPoint.proceed)

    // Jan Kronquist: started work on issue 121
    case Link(proxy)   => self.link(proxy)
    case Unlink(proxy) => self.unlink(proxy)
    case unexpected     => throw new IllegalActorStateException(
      "Unexpected message [" + unexpected + "] sent to [" + this + "]")
  }

  override def preRestart(reason: Throwable) {
    crashedActorTl.set(this)
    targetInstance.preRestart(reason)
  }

  override def postRestart(reason: Throwable) {
    targetInstance.postRestart(reason)
  }

  override def init {
    // Get the crashed dispatcher from thread local and intitialize this actor with the
    // contents of the old dispatcher
    val oldActor = crashedActorTl.get
    if (oldActor != null) {
      initialize(oldActor.targetClass, oldActor.targetInstance, oldActor.proxy, oldActor.context)
      crashedActorTl.set(null)
    }
  }

  override def shutdown {
    targetInstance.shutdown
    AspectInitRegistry.unregister(proxy);
  }

  override def initTransactionalState {
    targetInstance.initTransactionalState
  }

  def isTransactional(clazz: Class[_]): Boolean = 
    if (clazz == null) false
    else if (clazz.isAnnotationPresent(Annotations.transactionrequired)) true
    else isTransactional(clazz.getSuperclass)
    
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
      if (arg.getClass.getName.contains(TypedActor.AW_PROXY_PREFIX)) unserializable = true
    }
    if (!unserializable && hasMutableArgument) {
      val copyOfArgs = Serializer.Java.deepClone(args)
      joinPoint.getRtti.asInstanceOf[MethodRtti].setParameterValues(copyOfArgs.asInstanceOf[Array[AnyRef]])
    }
  }
}
