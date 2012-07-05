/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import Actor._
import akka.config.Supervision._
import akka.util._
import ReflectiveAccess._
import akka.transactor.{ Coordinated, Coordination, CoordinateException }
import akka.transactor.annotation.{ Coordinated ⇒ CoordinatedAnnotation }

import org.codehaus.aspectwerkz.joinpoint.{ MethodRtti, JoinPoint }
import org.codehaus.aspectwerkz.proxy.Proxy
import org.codehaus.aspectwerkz.annotation.{ Aspect, Around }

import java.net.InetSocketAddress
import java.lang.reflect.{ Method, Field, InvocationHandler, Proxy ⇒ JProxy }

import scala.reflect.BeanProperty
import akka.dispatch._

/**
 * TypedActor is a type-safe actor made out of a POJO with interface.
 * Void methods are turned into fire-forget messages.
 * Non-void methods are turned into request-reply messages with the exception of methods returning
 * a 'Future' which will be sent using request-reply-with-future semantics and need to return the
 * result using the 'future(..)' method: 'return future(... future result ...);'.
 * Methods returning akka.japi.Option will block until a timeout expires,
 * if the implementation of the method returns "none", some(null) will be returned, "none" will only be
 * returned when the method didn't respond within the timeout.
 *
 * Here is an example of usage (in Java):
 * <pre>
 * class TestActorImpl extends TypedActor implements TestActor {
 *
 *   public void hit(int count) {
 *     Pong pong = (Pong) context().sender();
 *     pong.hit(count++);
 *   }
 *
 *   public Future<Integer> square(int x) {
 *     return future(x * x);
 *   }
 *
 *   @Override
 *   public void preStart() {
 *     ... // optional initialization on start
 *   }
 *
 *   @Override
 *   public void postStop() {
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
 *   override def preStart() = {
 *     ... // optional initialization on start
 *   }
 *
 *   override def postStop() = {
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
abstract class TypedActor extends Actor with Proxyable {
  val DELEGATE_FIELD_NAME = "DELEGATE_0".intern

  @volatile
  private[akka] var proxy: AnyRef = _
  @volatile
  private var proxyDelegate: Field = _

  /**
   * Holds RTTI (runtime type information) for the TypedActor, f.e. current 'sender'
   * reference, the 'senderFuture' reference etc.
   * <p/>
   * This class does not contain static information but is updated by the runtime system
   * at runtime.
   * <p/>
   * You can get a hold of the context using the 'context()'
   * method from the 'TypedActor' base class.
   * <p/>
   *
   * Here is an example of usage (in Java):
   * <pre>
   * class PingImpl extends TypedActor implements Ping {
   *   public void hit(int count) {
   *     Pong pong = (Pong) context().sender();
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
  val context: TypedActorContext = new TypedActorContext(self)

  /**
   * @deprecated 'getContext()' is deprecated use 'context()'
   */
  def getContext: TypedActorContext = context

  /**
   * User overridable callback.
   * <p/>
   * Is called when an Actor is started by invoking 'actor.start()'.
   */
  override def preStart() {}

  /**
   * User overridable callback.
   * <p/>
   * Is called when 'actor.stop()' is invoked.
   */
  override def postStop() {}

  /**
   * User overridable callback.
   * <p/>
   * Is called on a crashed Actor right BEFORE it is restarted to allow clean up of resources before Actor is terminated.
   */
  override def preRestart(reason: Throwable) {}

  /**
   * User overridable callback.
   * <p/>
   * Is called right AFTER restart on the newly created Actor to allow reinitialization after an Actor crash.
   */
  override def postRestart(reason: Throwable) {}

  /**
   * This method is used to resolve the Future for TypedActor methods that are defined to return a
   * {@link akka.actor.dispatch.Future }.
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
  def future[T](value: T): Future[T] = self.channel match {
    case f: CompletableFuture[Any] ⇒ f.completeWithResult(value).asInstanceOf[Future[T]]
    case _                         ⇒ throw new IllegalActorStateException("No sender future in scope")
  }

  def receive = {
    case joinPoint: JoinPoint ⇒
      SenderContextInfo.senderActorRef.withValue(self) {
        SenderContextInfo.senderProxy.withValue(proxy) {
          if (Actor.SERIALIZE_MESSAGES) serializeArguments(joinPoint)
          if (TypedActor.isOneWay(joinPoint)) joinPoint.proceed
          else if (TypedActor.returnsFuture_?(joinPoint)) {
            val senderFuture = self.senderFuture
            joinPoint.proceed match {
              case f: Future[Any] ⇒ senderFuture.get.completeWith(f)
              case null           ⇒ senderFuture.get.completeWithResult(null)
            }
          } else {
            val channel = self.channel
            channel.tell(joinPoint.proceed)
          }
        }
      }

    case coordinated@Coordinated(joinPoint: JoinPoint) ⇒
      SenderContextInfo.senderActorRef.withValue(self) {
        SenderContextInfo.senderProxy.withValue(proxy) {
          if (Actor.SERIALIZE_MESSAGES) serializeArguments(joinPoint)
          coordinated atomic { joinPoint.proceed }
        }
      }

    case Link(proxy)   ⇒ self.link(proxy)
    case Unlink(proxy) ⇒ self.unlink(proxy)
    case unexpected ⇒ throw new IllegalActorStateException(
      "Unexpected message [" + unexpected + "] sent to [" + this + "]")
  }

  /**
   * Rewrite target instance in AspectWerkz Proxy.
   */
  private[actor] def swapProxiedActor(newInstance: Actor) = proxyDelegate.set(proxy, newInstance)

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
    for (arg ← args.toList) {
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

      //FIXME serializeArguments
      //    val copyOfArgs = Serializer.Java.deepClone(args)
      //    joinPoint.getRtti.asInstanceOf[MethodRtti].setParameterValues(copyOfArgs.asInstanceOf[Array[AnyRef]])
      joinPoint
    }
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
 *     Pong pong = (Pong) context().sender();
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
final class TypedActorContext(private[akka] val actorRef: ActorRef) {
  private[akka] var _sender: AnyRef = _

  /**
   * Returns the uuid for the actor.
   * @deprecated use 'uuid()'
   */
  def getUuid() = actorRef.uuid

  /**
   * 5  * Returns the uuid for the actor.
   */
  def uuid = actorRef.uuid

  def timeout = actorRef.timeout

  /**
   * @deprecated use 'timeout()'
   */
  def getTimout = timeout
  def setTimout(timeout: Long) = actorRef.timeout = timeout

  def id = actorRef.id

  /**
   * @deprecated use 'id()'
   */
  def getId = id
  def setId(id: String) = actorRef.id = id

  def receiveTimeout = actorRef.receiveTimeout

  /**
   * @deprecated use 'receiveTimeout()'
   */
  def getReceiveTimeout = receiveTimeout
  def setReceiveTimeout(timeout: Long) = actorRef.setReceiveTimeout(timeout)

  def mailboxSize = actorRef.mailboxSize

  def dispatcher = actorRef.getDispatcher

  def lifeCycle = actorRef.getLifeCycle

  def isRunning: Boolean = actorRef.isRunning
  def isShutdown: Boolean = actorRef.isShutdown
  def isUnstarted: Boolean = actorRef.isUnstarted
  def isBeingRestarted: Boolean = actorRef.isBeingRestarted

  def getSelfAs[T <: AnyRef](): T = TypedActor.proxyFor(actorRef).get.asInstanceOf[T]
  def getSelf(): AnyRef = getSelfAs[AnyRef]

  /**
   * Returns the current sender reference.
   * Scala style getter.
   */
  def sender: AnyRef = {
    if (_sender eq null) throw new IllegalActorStateException("Sender reference should not be null.")
    else _sender
  }

  /**
   * Returns the current sender future TypedActor reference.
   * Scala style getter.
   */
  def senderFuture: Option[CompletableFuture[Any]] = actorRef.senderFuture

  /**
   * Returns the current sender reference.
   * Java style getter.
   * @deprecated use 'sender()'
   */
  def getSender: AnyRef = {
    if (_sender eq null) throw new IllegalActorStateException("Sender reference should not be null.")
    else _sender
  }

  /**
   * Returns the current sender future TypedActor reference.
   * Java style getter.
   * This method returns 'null' if the sender future is not available.
   * @deprecated use 'senderFuture()'
   */
  def getSenderFuture = senderFuture

  /**
   * Returns the home address and port for this actor.
   */
  def homeAddress: InetSocketAddress = actorRef.homeAddress.getOrElse(null)
}

object TypedActorConfiguration {

  def apply(): TypedActorConfiguration = {
    new TypedActorConfiguration()
  }

  def apply(timeout: Long): TypedActorConfiguration = {
    new TypedActorConfiguration().timeout(Duration(timeout, "millis"))
  }

  @deprecated("Will be removed after 1.1", "1.1")
  def apply(host: String, port: Int): TypedActorConfiguration = {
    new TypedActorConfiguration().makeRemote(host, port)
  }

  @deprecated("Will be removed after 1.1", "1.1")
  def apply(host: String, port: Int, timeout: Long): TypedActorConfiguration = {
    new TypedActorConfiguration().makeRemote(host, port).timeout(Duration(timeout, "millis"))
  }
}

/**
 * Configuration factory for TypedActors.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
final class TypedActorConfiguration {
  private[akka] var _timeout: Long = Actor.TIMEOUT
  private[akka] var _host: Option[InetSocketAddress] = None
  private[akka] var _messageDispatcher: Option[MessageDispatcher] = None
  private[akka] var _threadBasedDispatcher: Option[Boolean] = None
  private[akka] var _id: Option[String] = None

  def timeout = _timeout
  def timeout(timeout: Duration): TypedActorConfiguration = {
    _timeout = timeout.toMillis
    this
  }

  def id = _id
  def id(id: String): TypedActorConfiguration = {
    _id = Option(id)
    this
  }

  @deprecated("Will be removed after 1.1", "1.1")
  def makeRemote(hostname: String, port: Int): TypedActorConfiguration = makeRemote(new InetSocketAddress(hostname, port))

  @deprecated("Will be removed after 1.1", "1.1")
  def makeRemote(remoteAddress: InetSocketAddress): TypedActorConfiguration = {
    _host = Some(remoteAddress)
    this
  }

  def dispatcher(messageDispatcher: MessageDispatcher): TypedActorConfiguration = {
    if (_threadBasedDispatcher.isDefined) throw new IllegalArgumentException(
      "Cannot specify both 'threadBasedDispatcher()' and 'dispatcher()'")
    _messageDispatcher = Some(messageDispatcher)
    this
  }

  def threadBasedDispatcher(): TypedActorConfiguration = {
    if (_messageDispatcher.isDefined) throw new IllegalArgumentException(
      "Cannot specify both 'threadBasedDispatcher()' and 'dispatcher()'")
    _threadBasedDispatcher = Some(true)
    this
  }
}

/**
 * Factory closure for an TypedActor, to be used with 'TypedActor.newInstance(interface, factory)'.
 *
 * @author michaelkober
 */
trait TypedActorFactory {
  def create: TypedActor
}

/**
 * Factory class for creating TypedActors out of plain POJOs and/or POJOs with interfaces.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object TypedActor {
  import Actor.actorOf

  val ZERO_ITEM_CLASS_ARRAY = Array[Class[_]]()
  val ZERO_ITEM_OBJECT_ARRAY = Array[Object]()

  val AKKA_CAMEL_ROUTING_SCHEME = "akka".intern
  val AW_PROXY_PREFIX = "$$ProxiedByAW".intern

  /**
   * Factory method for typed actor.
   * @param intfClass interface the typed actor implements
   * @param targetClass implementation class of the typed actor
   */
  def newInstance[T](intfClass: Class[T], targetClass: Class[_]): T =
    newInstance(intfClass, targetClass, TypedActorConfiguration())

  /**
   * Factory method for typed actor.
   * @param intfClass interface the typed actor implements
   * @param factory factory method that constructs the typed actor
   */
  def newInstance[T](intfClass: Class[T], factory: ⇒ AnyRef): T = {
    newInstance(intfClass, factory, TypedActorConfiguration())
  }

  /**
   * Factory method for remote typed actor.
   * @param intfClass interface the typed actor implements
   * @param targetClass implementation class of the typed actor
   * @param host hostname of the remote server
   * @param port port of the remote server
   */
  @deprecated("Will be removed after 1.1", "1.1")
  def newRemoteInstance[T](intfClass: Class[T], targetClass: Class[_], hostname: String, port: Int): T = {
    newInstance(intfClass, targetClass, TypedActorConfiguration(hostname, port))
  }

  /**
   * Factory method for remote typed actor.
   * @param intfClass interface the typed actor implements
   * @param factory factory method that constructs the typed actor
   * @param host hostname of the remote server
   * @param port port of the remote server
   */
  @deprecated("Will be removed after 1.1", "1.1")
  def newRemoteInstance[T](intfClass: Class[T], factory: ⇒ AnyRef, hostname: String, port: Int): T = {
    newInstance(intfClass, factory, TypedActorConfiguration(hostname, port))
  }

  /**
   * Factory method for typed actor.
   * @param intfClass interface the typed actor implements
   * @param targetClass implementation class of the typed actor
   * @param timeout timeout for future
   */
  def newInstance[T](intfClass: Class[T], targetClass: Class[_], timeout: Long): T = {
    newInstance(intfClass, targetClass, TypedActorConfiguration(timeout))
  }

  /**
   * Factory method for typed actor.
   * @param intfClass interface the typed actor implements
   * @param factory factory method that constructs the typed actor
   * @param timeout timeout for future
   */
  def newInstance[T](intfClass: Class[T], factory: ⇒ AnyRef, timeout: Long): T = {
    newInstance(intfClass, factory, TypedActorConfiguration(timeout))
  }

  /**
   * Factory method for remote typed actor.
   * @param intfClass interface the typed actor implements
   * @param targetClass implementation class of the typed actor
   * @param timeout timeout for future
   * @param host hostname of the remote server
   * @param port port of the remote server
   */
  @deprecated("Will be removed after 1.1", "1.1")
  def newRemoteInstance[T](intfClass: Class[T], targetClass: Class[_], timeout: Long, hostname: String, port: Int): T = {
    newInstance(intfClass, targetClass, TypedActorConfiguration(hostname, port, timeout))
  }

  /**
   * Factory method for remote typed actor.
   * @param intfClass interface the typed actor implements
   * @param factory factory method that constructs the typed actor
   * @param timeout timeout for future
   * @param host hostname of the remote server
   * @param port port of the remote server
   */
  @deprecated("Will be removed after 1.1", "1.1")
  def newRemoteInstance[T](intfClass: Class[T], factory: ⇒ AnyRef, timeout: Long, hostname: String, port: Int): T = {
    newInstance(intfClass, factory, TypedActorConfiguration(hostname, port, timeout))
  }

  /**
   * Factory method for typed actor.
   * @param intfClass interface the typed actor implements
   * @param factory factory method that constructs the typed actor
   * @param config configuration object for the typed actor
   */
  def newInstance[T](intfClass: Class[T], factory: ⇒ AnyRef, config: TypedActorConfiguration): T =
    newInstance(intfClass, createActorRef(newTypedActor(factory), config), config)

  /**
   * Creates an ActorRef, can be local only or client-managed-remote
   */
  @deprecated("Will be removed after 1.1", "1.1")
  private[akka] def createActorRef(typedActor: ⇒ TypedActor, config: TypedActorConfiguration): ActorRef = {
    config match {
      case null ⇒ actorOf(typedActor)
      case c: TypedActorConfiguration if (c._host.isDefined) ⇒
        Actor.remote.actorOf(typedActor, c._host.get.getAddress.getHostAddress, c._host.get.getPort)
      case _ ⇒ actorOf(typedActor)
    }
  }

  /**
   *  Factory method for typed actor.
   * @param intfClass interface the typed actor implements
   * @param targetClass implementation class of the typed actor
   * @param config configuration object for the typed actor
   */
  def newInstance[T](intfClass: Class[T], targetClass: Class[_], config: TypedActorConfiguration): T =
    newInstance(intfClass, createActorRef(newTypedActor(targetClass), config), config)

  private[akka] def newInstance[T](intfClass: Class[T], actorRef: ActorRef): T = {
    if (!actorRef.actorInstance.get.isInstanceOf[TypedActor]) throw new IllegalArgumentException("ActorRef is not a ref to a typed actor")
    newInstance(intfClass, actorRef, TypedActorConfiguration())
  }

  private[akka] def newInstance[T](intfClass: Class[T], targetClass: Class[_],
                                   remoteAddress: Option[InetSocketAddress], timeout: Long): T = {
    val config = TypedActorConfiguration(timeout)
    if (remoteAddress.isDefined) config.makeRemote(remoteAddress.get)
    newInstance(intfClass, targetClass, config)
  }

  private def newInstance[T](intfClass: Class[T], actorRef: ActorRef, config: TypedActorConfiguration): T = {
    val typedActor = actorRef.actorInstance.get.asInstanceOf[TypedActor]
    val proxy = Proxy.newInstance(Array(intfClass), Array(typedActor), true, false)
    typedActor.initialize(proxy)
    if (config._messageDispatcher.isDefined) actorRef.dispatcher = config._messageDispatcher.get
    if (config._threadBasedDispatcher.isDefined) actorRef.dispatcher = Dispatchers.newThreadBasedDispatcher(actorRef)
    if (config._id.isDefined) actorRef.id = config._id.get

    actorRef.timeout = config.timeout

    val remoteAddress = actorRef match {
      case remote: RemoteActorRef ⇒ remote.homeAddress
      case local: LocalActorRef if local.clientManaged ⇒ local.homeAddress
      case _ ⇒ None
    }

    AspectInitRegistry.register(proxy, AspectInit(intfClass, typedActor, actorRef, remoteAddress, actorRef.timeout))
    actorRef.start()
    proxy.asInstanceOf[T]
  }

  /**
   * Java API.
   * NOTE: Use this convenience method with care, do NOT make it possible to get a reference to the
   * TypedActor instance directly, but only through its 'ActorRef' wrapper reference.
   * <p/>
   * Creates an ActorRef out of the Actor. Allows you to pass in the instance for the TypedActor.
   * Only use this method when you need to pass in constructor arguments into the 'TypedActor'.
   * <p/>
   * You use it by implementing the TypedActorFactory interface.
   * Example in Java:
   * <pre>
   *   MyPojo pojo = TypedActor.newInstance(MyPojo.class, new TypedActorFactory() {
   *     public TypedActor create() {
   *       return new MyTypedActor("service:name", 5);
   *     }
   *   });
   * </pre>
   */
  def newInstance[T](intfClass: Class[T], factory: TypedActorFactory): T =
    newInstance(intfClass, factory.create)

  /**
   * Java API.
   */
  @deprecated("Will be removed after 1.1", "1.1")
  def newRemoteInstance[T](intfClass: Class[T], factory: TypedActorFactory, hostname: String, port: Int): T =
    newRemoteInstance(intfClass, factory.create, hostname, port)

  /**
   * Java API.
   */
  @deprecated("Will be removed after 1.1", "1.1")
  def newRemoteInstance[T](intfClass: Class[T], factory: TypedActorFactory, timeout: Long, hostname: String, port: Int): T =
    newRemoteInstance(intfClass, factory.create, timeout, hostname, port)

  /**
   * Java API.
   */
  def newInstance[T](intfClass: Class[T], factory: TypedActorFactory, timeout: Long): T =
    newInstance(intfClass, factory.create, timeout)

  /**
   * Java API.
   */
  def newInstance[T](intfClass: Class[T], factory: TypedActorFactory, config: TypedActorConfiguration): T =
    newInstance(intfClass, factory.create, config)

  /**
   * Create a proxy for a RemoteActorRef representing a server managed remote typed actor.
   *
   */
  private[akka] def createProxyForRemoteActorRef[T](intfClass: Class[T], actorRef: ActorRef): T = {

    class MyInvocationHandler extends InvocationHandler {
      def invoke(proxy: AnyRef, method: Method, args: Array[AnyRef]): AnyRef = {
        // do nothing, this is just a dummy
        null
      }
    }
    val handler = new MyInvocationHandler()

    val interfaces = Array(intfClass, classOf[ServerManagedTypedActor]).asInstanceOf[Array[java.lang.Class[_]]]
    val jProxy = JProxy.newProxyInstance(intfClass.getClassLoader(), interfaces, handler)
    val awProxy = Proxy.newInstance(interfaces, Array(jProxy, jProxy), true, false)

    AspectInitRegistry.register(awProxy, AspectInit(intfClass, null, actorRef, actorRef.homeAddress, actorRef.timeout))
    awProxy.asInstanceOf[T]
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
      actorRef.start()
      proxy.asInstanceOf[T]
    }
  */

  /**
   * Stops the current Typed Actor.
   */
  def stop(proxy: AnyRef): Unit = AspectInitRegistry.unregister(proxy)

  /**
   * Sends a PoisonPill to the TypedActor
   */
  def poisonPill(proxy: AnyRef) {
    AspectInitRegistry.initFor(proxy) match {
      case null ⇒
      case a: AspectInit ⇒ a.actorRef !!! (PoisonPill, Long.MaxValue) failure {
        case ake: ActorKilledException ⇒ stop(proxy)
      }
    }
  }

  /**
   * Get the underlying typed actor for the given Typed Actor.
   */
  def actorFor(proxy: AnyRef): Option[ActorRef] =
    Actor.registry find {
      case a if a.actor.isInstanceOf[TypedActor] && a.actor.asInstanceOf[TypedActor].proxy == proxy ⇒
        a
    }

  /**
   * Get the typed actor proxy for the given Typed Actor.
   */
  def proxyFor(actorRef: ActorRef): Option[AnyRef] = {
    if (actorRef.actor.isInstanceOf[TypedActor]) {
      Some(actorRef.actor.asInstanceOf[TypedActor].proxy)
    } else {
      None
    }
  }

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
           handler: FaultHandlingStrategy) = {
    val supervisorActor = actorFor(supervisor).getOrElse(
      throw new IllegalActorStateException("Can't link when the supervisor is not an Typed Actor"))
    val supervisedActor = actorFor(supervised).getOrElse(
      throw new IllegalActorStateException("Can't link when the supervised is not an Typed Actor"))
    supervisorActor.faultHandler = handler
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
   * Sets the fault handling strategy for the given supervisor Typed Actor.
   * @param supervisor the supervisor Typed Actor
   * @param handler fault handling strategy
   */
  def faultHandler(supervisor: AnyRef, handler: FaultHandlingStrategy) = {
    val supervisorActor = actorFor(supervisor).getOrElse(
      throw new IllegalActorStateException("Can't set fault handler when the supervisor is not an Typed Actor"))
    supervisorActor.faultHandler = handler
    this
  }

  private[akka] def newTypedActor(targetClass: Class[_]): TypedActor = {
    val instance = targetClass.newInstance
    val typedActor =
      if (instance.isInstanceOf[TypedActor]) instance.asInstanceOf[TypedActor]
      else throw new IllegalArgumentException("Actor [" + targetClass.getName + "] is not a sub class of 'TypedActor'")
    typedActor
  }

  private[akka] def newTypedActor(factory: ⇒ AnyRef): TypedActor = {
    val instance = factory
    val typedActor =
      if (instance.isInstanceOf[TypedActor]) instance.asInstanceOf[TypedActor]
      else throw new IllegalArgumentException("Actor [" + instance.getClass.getName + "] is not a sub class of 'TypedActor'")
    typedActor
  }

  private[akka] def isOneWay(joinPoint: JoinPoint): Boolean =
    isOneWay(joinPoint.getRtti.asInstanceOf[MethodRtti])

  private[akka] def isOneWay(methodRtti: MethodRtti): Boolean =
    methodRtti.getMethod.getReturnType == java.lang.Void.TYPE

  private[akka] def isCoordinated(joinPoint: JoinPoint): Boolean =
    isCoordinated(joinPoint.getRtti.asInstanceOf[MethodRtti])

  private[akka] def isCoordinated(methodRtti: MethodRtti): Boolean =
    methodRtti.getMethod.isAnnotationPresent(classOf[CoordinatedAnnotation])

  private[akka] def returnsFuture_?(joinPoint: JoinPoint): Boolean =
    returnsFuture_?(joinPoint.getRtti.asInstanceOf[MethodRtti])

  private[akka] def returnsFuture_?(methodRtti: MethodRtti): Boolean =
    classOf[Future[_]].isAssignableFrom(methodRtti.getMethod.getReturnType)

  private[akka] def returnsOption_?(methodRtti: MethodRtti): Boolean =
    classOf[akka.japi.Option[_]].isAssignableFrom(methodRtti.getMethod.getReturnType)

  private[akka] def supervise(faultHandlingStrategy: FaultHandlingStrategy, components: List[Supervise]): Supervisor =
    Supervisor(SupervisorConfig(faultHandlingStrategy, components))

  def isJoinPointAndOneWay(message: Any): Boolean = if (isJoinPoint(message))
    isOneWay(message.asInstanceOf[JoinPoint].getRtti.asInstanceOf[MethodRtti])
  else false

  private[akka] def isJoinPoint(message: Any): Boolean = message.isInstanceOf[JoinPoint]
}

/**
 * AspectWerkz Aspect that is turning POJO into proxy to a server managed remote TypedActor.
 * <p/>
 * Is deployed on a 'perInstance' basis with the pointcut 'execution(* *.*(..))',
 * e.g. all methods on the instance.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@Aspect("perInstance")
private[akka] sealed class ServerManagedTypedActorAspect extends ActorAspect {

  @Around("execution(* *.*(..)) && this(akka.actor.ServerManagedTypedActor)")
  def invoke(joinPoint: JoinPoint): AnyRef = {
    initialize(joinPoint)
    remoteDispatch(joinPoint)
  }

  override def initialize(joinPoint: JoinPoint): Unit = {
    super.initialize(joinPoint)
    //remoteAddress = actorRef.remoteAddress //TODO: REVISIT: Fix Server managed Typed Actor
  }
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
private[akka] sealed class TypedActorAspect extends ActorAspect {

  @Around("execution(* *.*(..)) && !this(akka.actor.ServerManagedTypedActor)")
  def invoke(joinPoint: JoinPoint): AnyRef = {
    initialize(joinPoint)
    dispatch(joinPoint)
  }

  private def dispatch(joinPoint: JoinPoint) = {
    if (remoteAddress.isDefined) remoteDispatch(joinPoint)
    else localDispatch(joinPoint)
  }
}

/**
 * Base class for TypedActorAspect and ServerManagedTypedActorAspect to reduce code duplication.
 */
private[akka] abstract class ActorAspect {
  protected val isInitialized = new Switch(false)
  protected val isStopped = new Switch(false)
  protected var interfaceClass: Class[_] = _
  protected var typedActor: TypedActor = _
  protected var actorRef: ActorRef = _
  protected var timeout: Long = _
  protected var uuid: Uuid = _
  protected var remoteAddress: Option[InetSocketAddress] = _

  protected def localDispatch(joinPoint: JoinPoint): AnyRef = {
    val methodRtti = joinPoint.getRtti.asInstanceOf[MethodRtti]
    val isOneWay = TypedActor.isOneWay(methodRtti)
    val senderActorRef = Option(SenderContextInfo.senderActorRef.value)
    val senderProxy = Option(SenderContextInfo.senderProxy.value)
    val isCoordinated = TypedActor.isCoordinated(methodRtti)

    typedActor.context._sender = senderProxy

    if (!isStopped.isOn && !actorRef.isRunning) {
      isStopped switchOn {
        val proxy = TypedActor.proxyFor(actorRef)
        if (proxy ne null)
          TypedActor.stop(proxy)
      }
    }

    if (isOneWay && isCoordinated) {
      val coordinatedOpt = Option(Coordination.coordinated.value)
      val coordinated = coordinatedOpt.map(coord ⇒
        if (Coordination.firstParty.value) { // already included in coordination
          Coordination.firstParty.value = false
          coord.noIncrement(joinPoint)
        } else {
          coord(joinPoint)
        }).getOrElse(Coordinated(joinPoint))

      actorRef.!(coordinated)(senderActorRef)
      null.asInstanceOf[AnyRef]

    } else if (isCoordinated) {
      throw new CoordinateException("Can't use @Coordinated annotation with non-void methods.")
    } else if (isOneWay) {
      actorRef.!(joinPoint)(senderActorRef)
      null.asInstanceOf[AnyRef]
    } else if (TypedActor.returnsFuture_?(methodRtti)) {
      actorRef.!!!(joinPoint, timeout)(senderActorRef)
    } else if (TypedActor.returnsOption_?(methodRtti)) {
      import akka.japi.{ Option ⇒ JOption }
      (actorRef.!!(joinPoint, timeout)(senderActorRef)).as[JOption[AnyRef]] match {
        case None                                  ⇒ JOption.none[AnyRef]
        case Some(x) if ((x eq null) || x.isEmpty) ⇒ JOption.some[AnyRef](null)
        case Some(x)                               ⇒ x
      }
    } else {
      val result = (actorRef.!!(joinPoint, timeout)(senderActorRef)).as[AnyRef]
      if (result.isDefined) result.get
      else throw new ActorTimeoutException("Invocation to [" + joinPoint + "] timed out.")
    }
  }

  protected def remoteDispatch(joinPoint: JoinPoint): AnyRef = {
    val methodRtti = joinPoint.getRtti.asInstanceOf[MethodRtti]
    val isOneWay = TypedActor.isOneWay(methodRtti)
    val senderActorRef = Option(SenderContextInfo.senderActorRef.value)

    def extractOwnerTypeHint(s: String) =
      s.indexOf(TypedActor.AW_PROXY_PREFIX) match {
        case -1 ⇒ s
        case x  ⇒ s.substring(0, x + TypedActor.AW_PROXY_PREFIX.length)
      }
    //FIXME: Add ownerTypeHint and parameter types to the TypedActorInfo?
    val message: Tuple3[String, Array[Class[_]], Array[AnyRef]] =
      ((extractOwnerTypeHint(methodRtti.getMethod.getDeclaringClass.getName),
        methodRtti.getParameterTypes,
        methodRtti.getParameterValues))

    //FIXME send the interface name of the senderProxy in the TypedActorContext and assemble a context.sender with that interface on the server
    //val senderProxy = Option(SenderContextInfo.senderProxy.value)

    val future_? = Actor.remote.send[AnyRef](
      message, senderActorRef, None, remoteAddress.get,
      timeout, isOneWay, actorRef,
      Some((interfaceClass.getName, methodRtti.getMethod.getName)),
      ActorType.TypedActor,
      None) //TODO: REVISIT: Use another classloader?

    if (isOneWay) null // for void methods
    else if (future_?.isEmpty) throw new IllegalActorStateException("No future returned from call to [" + joinPoint + "]")
    else if (TypedActor.returnsFuture_?(methodRtti)) future_?.get
    else if (TypedActor.returnsOption_?(methodRtti)) {
      import akka.japi.{ Option ⇒ JOption }
      future_?.get.await.resultOrException.as[JOption[AnyRef]] match {
        case None                                  ⇒ JOption.none[AnyRef]
        case Some(x) if ((x eq null) || x.isEmpty) ⇒ JOption.some[AnyRef](null)
        case Some(x)                               ⇒ x
      }
    } else {
      val result = future_?.get.await.resultOrException
      if (result.isDefined) result.get
      else throw new IllegalActorStateException("No result returned from call to [" + joinPoint + "]")
    }
  }

  protected def initialize(joinPoint: JoinPoint) {
    isInitialized.switchOn {
      val init = AspectInitRegistry.initFor(joinPoint.getThis)
      interfaceClass = init.interfaceClass
      typedActor = init.targetInstance
      actorRef = init.actorRef
      uuid = actorRef.uuid
      remoteAddress = init.remoteAddress
      timeout = init.timeout
    }
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
  private[actor] val senderProxy = new DynamicVariable[AnyRef](null)
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
private[akka] object AspectInitRegistry extends ListenerManagement {
  private val initializations = new java.util.concurrent.ConcurrentHashMap[AnyRef, AspectInit]

  def initFor(proxy: AnyRef) = initializations.get(proxy)

  def register(proxy: AnyRef, init: AspectInit) = {
    val res = initializations.put(proxy, init)
    notifyListeners(AspectInitRegistered(proxy, init))
    res
  }

  /**
   * Unregisters initialization and stops its ActorRef.
   */
  def unregister(proxy: AnyRef): AspectInit = {
    val init = if (proxy ne null) initializations.remove(proxy) else null
    if (init ne null) {
      notifyListeners(AspectInitUnregistered(proxy, init))
      if (!init.actorRef.isShutdown)
        init.actorRef.stop()
    }
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

/**
 * Marker interface for server manager typed actors.
 */
private[akka] sealed trait ServerManagedTypedActor extends TypedActor
