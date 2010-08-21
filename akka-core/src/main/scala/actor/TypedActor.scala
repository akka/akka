/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.actor

import Actor._
import se.scalablesolutions.akka.config.FaultHandlingStrategy
import se.scalablesolutions.akka.remote.protocol.RemoteProtocol._
import se.scalablesolutions.akka.remote.{MessageSerializer, RemoteClient, RemoteRequestProtocolIdFactory}
import se.scalablesolutions.akka.dispatch.{MessageDispatcher, Future, CompletableFuture, Dispatchers}
import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.serialization.Serializer
import se.scalablesolutions.akka.util._

import org.codehaus.aspectwerkz.joinpoint.{MethodRtti, JoinPoint}
import org.codehaus.aspectwerkz.proxy.Proxy
import org.codehaus.aspectwerkz.annotation.{Aspect, Around}

import java.net.InetSocketAddress
import java.lang.reflect.{InvocationTargetException, Method, Field}

import scala.reflect.BeanProperty

/**
 * TypedActor is a type-safe actor made out of a POJO with interface.
 * Void methods are turned into fire-forget messages.
 * Non-void methods are turned into request-reply messages with the exception of methods returning
 * a 'Future' which will be sent using request-reply-with-future semantics and need to return the
 * result using the 'future(..)' method: 'return future(... future result ...);'.
 *
 * Here is an example of usage (in Java):
 * <pre>
 * class TestActorImpl extends TypedActor implements TestActor {
 *
 *   public void hit(int count) {
 *     Pong pong = (Pong) getContext().getSender();
 *     pong.hit(count++);
 *   }
 *
 *   public Future<Integer> square(int x) {
 *     return future(x * x);
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
 * TestActor actor = TypedActor.newInstance(TestActor.class, TestActorImpl.class);
 *
 * actor.hit(1); // use the actor
 * actor.hit(1);
 *
 * // This method will return immediately when called, caller should wait on the Future for the result
 * Future<Integer> future = actor.square(10);
 * future.await();
 * Integer result = future.get();
 *
 * // stop the actor
 * TypedActor.stop(actor);
 * </pre>
 *
 * Here is an example of usage (in Scala):
 * <pre>
 * class TestActorImpl extends TypedActor with TestActor {
 *
 *   def hit(count: Int) = {
 *     val pong = context.sender.asInstanceOf[Pong]
 *     pong.hit(count += 1)
 *   }
 *
 *   def square(x: Int): Future[Integer] = future(x * x)
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
 * // This method will return immediately when called, caller should wait on the Future for the result
 * val future = actor.square(10)
 * future.await
 * val result: Int = future.get
 *
 * // stop the actor
 * TypedActor.stop(ping)
 * </pre>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
abstract class TypedActor extends Actor {
  val DELEGATE_FIELD_NAME = "DELEGATE_0".intern

  @volatile private[actor] var proxy: AnyRef = _
  @volatile private var proxyDelegate: Field = _

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
  @BeanProperty val context: TypedActorContext = new TypedActorContext(self)

  /**
   * This method is used to resolve the Future for TypedActor methods that are defined to return a
   * {@link se.scalablesolutions.akka.actor.dispatch.Future }.
   * <p/>
   * Here is an example:
   * <pre>
   *   class MyTypedActorImpl extends TypedActor implements MyTypedActor {
   *     public Future<Integer> square(int x) {
   *       return future(x * x);
   *    }
   *  }
   *
   *  MyTypedActor actor = TypedActor.actorOf(MyTypedActor.class, MyTypedActorImpl.class);
   *
   *  // This method will return immediately when called, caller should wait on the Future for the result
   *  Future<Integer> future = actor.square(10);
   *  future.await();
   *  Integer result = future.get();
   * </pre>
   */
  def future[T](value: T): Future[T] =
    self.senderFuture
      .map{f => f.completeWithResult(value); f }
      .getOrElse(throw new IllegalActorStateException("No sender future in scope"))
      .asInstanceOf[Future[T]]

  def receive = {
    case joinPoint: JoinPoint =>
      SenderContextInfo.senderActorRef.value = self
      SenderContextInfo.senderProxy.value    = proxy

      if (Actor.SERIALIZE_MESSAGES)       serializeArguments(joinPoint)
      if (TypedActor.isOneWay(joinPoint)) joinPoint.proceed
      else                                self.reply(joinPoint.proceed)

    case Link(proxy)   => self.link(proxy)
    case Unlink(proxy) => self.unlink(proxy)
    case unexpected    => throw new IllegalActorStateException(
      "Unexpected message [" + unexpected + "] sent to [" + this + "]")
  }

  /**
   * Rewrite target instance in AspectWerkz Proxy.
   */
  private[actor] def swapInstanceInProxy(newInstance: Actor) = proxyDelegate.set(proxy, newInstance)

  private[akka] def initialize(typedActorProxy: AnyRef) = {
    proxy = typedActorProxy
    proxyDelegate = {
      val field = proxy.getClass.getDeclaredField(DELEGATE_FIELD_NAME)
      field.setAccessible(true)
      field
    }
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
          !arg.isInstanceOf[java.lang.Character]) hasMutableArgument = true
      if (arg.getClass.getName.contains(TypedActor.AW_PROXY_PREFIX)) unserializable = true
    }
    if (!unserializable && hasMutableArgument) {
      val copyOfArgs = Serializer.Java.deepClone(args)
      joinPoint.getRtti.asInstanceOf[MethodRtti].setParameterValues(copyOfArgs.asInstanceOf[Array[AnyRef]])
    }
  }
}

/**
 * Transactional TypedActor. All messages send to this actor as sent in a transaction. If an enclosing transaction
 * exists it will be joined, if not then a new transaction will be created.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
abstract class TypedTransactor extends TypedActor {
  self.makeTransactionRequired
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
final class TypedActorContext(private val actorRef: ActorRef) {
  private[akka] var _sender: AnyRef = _

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
  def senderFuture: Option[CompletableFuture[Any]] = actorRef.senderFuture

  /**
   * Returns the current sender future TypedActor reference.
   * Java style getter.
   * This method returns 'null' if the sender future is not available.
   */
  def getSenderFuture = senderFuture
}

/**
 * Configuration factory for TypedActors.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
final class TypedActorConfiguration {
  private[akka] var _timeout: Long = Actor.TIMEOUT
  private[akka] var _transactionRequired = false
  private[akka] var _host: Option[InetSocketAddress] = None
  private[akka] var _messageDispatcher: Option[MessageDispatcher] = None
  private[akka] var _threadBasedDispatcher: Option[Boolean] = None

  def timeout = _timeout
  def timeout(timeout: Duration) : TypedActorConfiguration = {
    _timeout = timeout.toMillis
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
    if (_threadBasedDispatcher.isDefined) throw new IllegalArgumentException(
      "Cannot specify both 'threadBasedDispatcher()' and 'dispatcher()'")
    _messageDispatcher = Some(messageDispatcher)
    this
  }

  def threadBasedDispatcher() : TypedActorConfiguration = {
    if (_messageDispatcher.isDefined) throw new IllegalArgumentException(
      "Cannot specify both 'threadBasedDispatcher()' and 'dispatcher()'")
    _threadBasedDispatcher = Some(true)
    this
  }
}

/**
 * Factory class for creating TypedActors out of plain POJOs and/or POJOs with interfaces.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object TypedActor extends Logging {
  import Actor.actorOf

  val ZERO_ITEM_CLASS_ARRAY = Array[Class[_]]()
  val ZERO_ITEM_OBJECT_ARRAY = Array[Object]()

  val AKKA_CAMEL_ROUTING_SCHEME = "akka".intern
  private[actor] val AW_PROXY_PREFIX = "$$ProxiedByAW".intern

  def newInstance[T](intfClass: Class[T], targetClass: Class[_]): T = {
    newInstance(intfClass, targetClass, None, Actor.TIMEOUT)
  }

  def newRemoteInstance[T](intfClass: Class[T], targetClass: Class[_], hostname: String, port: Int): T = {
    newInstance(intfClass, targetClass, Some(new InetSocketAddress(hostname, port)), Actor.TIMEOUT)
  }

  def newInstance[T](intfClass: Class[T], targetClass: Class[_], timeout: Long = Actor.TIMEOUT): T = {
    newInstance(intfClass, targetClass, None, timeout)
  }

  def newRemoteInstance[T](intfClass: Class[T], targetClass: Class[_], timeout: Long = Actor.TIMEOUT, hostname: String, port: Int): T = {
    newInstance(intfClass, targetClass, Some(new InetSocketAddress(hostname, port)), timeout)
  }

  def newInstance[T](intfClass: Class[T], targetClass: Class[_], config: TypedActorConfiguration): T = {
    val actorRef = actorOf(newTypedActor(targetClass))
    val typedActor = actorRef.actorInstance.get.asInstanceOf[TypedActor]
    val proxy = Proxy.newInstance(Array(intfClass), Array(typedActor), true, false)
    typedActor.initialize(proxy)
    if (config._messageDispatcher.isDefined) actorRef.dispatcher = config._messageDispatcher.get
    if (config._threadBasedDispatcher.isDefined) actorRef.dispatcher = Dispatchers.newThreadBasedDispatcher(actorRef)
    AspectInitRegistry.register(proxy, AspectInit(intfClass, typedActor, actorRef, None, config.timeout))
    actorRef.start
    proxy.asInstanceOf[T]
  }

  private[akka] def newInstance[T](intfClass: Class[T], targetClass: Class[_],
                                   remoteAddress: Option[InetSocketAddress], timeout: Long): T = {
    val actorRef = actorOf(newTypedActor(targetClass))
    val typedActor = actorRef.actorInstance.get.asInstanceOf[TypedActor]
    val proxy = Proxy.newInstance(Array(intfClass), Array(typedActor), true, false)
    typedActor.initialize(proxy)
    actorRef.timeout = timeout
    if (remoteAddress.isDefined) actorRef.makeRemote(remoteAddress.get)
    AspectInitRegistry.register(proxy, AspectInit(intfClass, typedActor, actorRef, remoteAddress, timeout))
    actorRef.start
    proxy.asInstanceOf[T]
  }

/*
  // NOTE: currently not used - but keep it around
  private[akka] def newInstance[T <: TypedActor](targetClass: Class[T],
                                                 remoteAddress: Option[InetSocketAddress], timeout: Long): T = {
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
*/

  /**
   * Stops the current Typed Actor.
   */
  def stop(proxy: AnyRef): Unit = AspectInitRegistry.unregister(proxy)

  /**
   * Get the underlying dispatcher actor for the given Typed Actor.
   */
  def actorFor(proxy: AnyRef): Option[ActorRef] =
    ActorRegistry
      .actorsFor(classOf[TypedActor])
      .find(a => a.actor.asInstanceOf[TypedActor].proxy == proxy)

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

  def isTransactional(clazz: Class[_]): Boolean = {
    if (clazz == null) false
    else if (clazz.isAssignableFrom(classOf[TypedTransactor])) true
    else isTransactional(clazz.getSuperclass)
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

  private[akka] def isOneWay(joinPoint: JoinPoint): Boolean =
    isOneWay(joinPoint.getRtti.asInstanceOf[MethodRtti])

  private[akka] def isOneWay(methodRtti: MethodRtti): Boolean =
    methodRtti.getMethod.getReturnType == java.lang.Void.TYPE

  private[akka] def returnsFuture_?(methodRtti: MethodRtti): Boolean =
    classOf[Future[_]].isAssignableFrom(methodRtti.getMethod.getReturnType)

  private[akka] def supervise(restartStrategy: RestartStrategy, components: List[Supervise]): Supervisor =
    Supervisor(SupervisorConfig(restartStrategy, components))
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
  private var typedActor: TypedActor = _
  private var actorRef: ActorRef = _
  private var remoteAddress: Option[InetSocketAddress] = _
  private var timeout: Long = _
  private var uuid: String = _
  @volatile private var instance: TypedActor = _

  @Around("execution(* *.*(..))")
  def invoke(joinPoint: JoinPoint): AnyRef = {
    if (!isInitialized) initialize(joinPoint)
    dispatch(joinPoint)
  }

  private def dispatch(joinPoint: JoinPoint) = {
    if (remoteAddress.isDefined) remoteDispatch(joinPoint)
    else localDispatch(joinPoint)
  }

  private def localDispatch(joinPoint: JoinPoint): AnyRef = {
    val methodRtti     = joinPoint.getRtti.asInstanceOf[MethodRtti]
    val isOneWay       = TypedActor.isOneWay(methodRtti)
    val senderActorRef = Some(SenderContextInfo.senderActorRef.value)
    val senderProxy    = Some(SenderContextInfo.senderProxy.value)

    typedActor.context._sender = senderProxy
    if (!actorRef.isRunning && !isStopped) {
      isStopped = true
      joinPoint.proceed

    } else if (isOneWay) {
      actorRef.!(joinPoint)(senderActorRef)
      null.asInstanceOf[AnyRef]

    } else if (TypedActor.returnsFuture_?(methodRtti)) {
      actorRef.!!!(joinPoint, timeout)(senderActorRef)

    } else {
      val result = (actorRef.!!(joinPoint, timeout)(senderActorRef)).as[AnyRef]
      if (result.isDefined) result.get
      else throw new ActorTimeoutException("Invocation to [" + joinPoint + "] timed out.")
    }
  }

  private def remoteDispatch(joinPoint: JoinPoint): AnyRef = {
    val methodRtti = joinPoint.getRtti.asInstanceOf[MethodRtti]
    val isOneWay = TypedActor.isOneWay(methodRtti)
    val (message: Array[AnyRef], isEscaped) = escapeArguments(methodRtti.getParameterValues)

    val typedActorInfo = TypedActorInfoProtocol.newBuilder
        .setInterface(interfaceClass.getName)
        .setMethod(methodRtti.getMethod.getName)
        .build

    val actorInfo = ActorInfoProtocol.newBuilder
        .setUuid(uuid)
        .setTarget(typedActor.getClass.getName)
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
    if (future.exception.isDefined) throw future.exception.get
    else future.result

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

  private def initialize(joinPoint: JoinPoint): Unit = {
    val init = AspectInitRegistry.initFor(joinPoint.getThis)
    interfaceClass = init.interfaceClass
    typedActor = init.targetInstance
    actorRef = init.actorRef
    uuid = actorRef.uuid
    remoteAddress = init.remoteAddress
    timeout = init.timeout
    isInitialized = true
  }
}

/**
 * Internal helper class to help pass the contextual information between threads.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
private[akka] object SenderContextInfo {
  import scala.util.DynamicVariable
  private[actor] val senderActorRef = new DynamicVariable[ActorRef](null)
  private[actor] val senderProxy    = new DynamicVariable[AnyRef](null)
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

  /**
   * Unregisters initialization and stops its ActorRef.
   */
  def unregister(proxy: AnyRef): AspectInit = {
    val init = initializations.remove(proxy)
    foreachListener(_ ! AspectInitUnregistered(proxy, init))
    init.actorRef.stop
    init
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

