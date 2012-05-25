package akka.actor

/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

import akka.japi.{ Creator, Option ⇒ JOption }
import java.lang.reflect.{ InvocationTargetException, Method, InvocationHandler, Proxy }
import akka.util.{ Timeout, NonFatal }
import java.util.concurrent.atomic.{ AtomicReference ⇒ AtomVar }
import akka.serialization.{ Serialization, SerializationExtension }
import akka.dispatch._
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.lang.IllegalStateException
import akka.util.Duration

trait TypedActorFactory {

  protected def actorFactory: ActorRefFactory

  protected def typedActor: TypedActorExtension

  /**
   * Stops the underlying ActorRef for the supplied TypedActor proxy,
   * if any, returns whether it could find the find the ActorRef or not
   */
  def stop(proxy: AnyRef): Boolean = getActorRefFor(proxy) match {
    case null ⇒ false
    case ref  ⇒ ref.asInstanceOf[InternalActorRef].stop; true
  }

  /**
   * Sends a PoisonPill the underlying ActorRef for the supplied TypedActor proxy,
   * if any, returns whether it could find the find the ActorRef or not
   */
  def poisonPill(proxy: AnyRef): Boolean = getActorRefFor(proxy) match {
    case null ⇒ false
    case ref  ⇒ ref ! PoisonPill; true
  }

  /**
   * Returns wether the supplied AnyRef is a TypedActor proxy or not
   */
  def isTypedActor(proxyOrNot: AnyRef): Boolean

  /**
   * Retrieves the underlying ActorRef for the supplied TypedActor proxy, or null if none found
   */
  def getActorRefFor(proxy: AnyRef): ActorRef

  /**
   * Creates a new TypedActor with the specified properties
   */
  def typedActorOf[R <: AnyRef, T <: R](props: TypedProps[T]): R = {
    val proxyVar = new AtomVar[R] //Chicken'n'egg-resolver
    val c = props.creator //Cache this to avoid closing over the Props
    val ap = props.actorProps.withCreator(new TypedActor.TypedActor[R, T](proxyVar, c()))
    typedActor.createActorRefProxy(props, proxyVar, actorFactory.actorOf(ap))
  }

  /**
   * Creates a new TypedActor with the specified properties
   */
  def typedActorOf[R <: AnyRef, T <: R](props: TypedProps[T], name: String): R = {
    val proxyVar = new AtomVar[R] //Chicken'n'egg-resolver
    val c = props.creator //Cache this to avoid closing over the Props
    val ap = props.actorProps.withCreator(new akka.actor.TypedActor.TypedActor[R, T](proxyVar, c()))
    typedActor.createActorRefProxy(props, proxyVar, actorFactory.actorOf(ap, name))
  }

  /**
   * Creates a TypedActor that intercepts the calls and forwards them as [[akka.actor.TypedActor.MethodCall]]
   * to the provided ActorRef.
   */
  def typedActorOf[R <: AnyRef, T <: R](props: TypedProps[T], actorRef: ActorRef): R =
    typedActor.createActorRefProxy(props, null: AtomVar[R], actorRef)

}

object TypedActor extends ExtensionId[TypedActorExtension] with ExtensionIdProvider {
  override def get(system: ActorSystem): TypedActorExtension = super.get(system)

  def lookup() = this
  def createExtension(system: ExtendedActorSystem): TypedActorExtension = new TypedActorExtension(system)

  /**
   * Returns a contextual TypedActorFactory of this extension, this means that any TypedActors created by this TypedActorExtension
   * will be children to the specified context, this allows for creating hierarchies of TypedActors.
   * Do _not_ let this instance escape the TypedActor since that will not be thread-safe.
   */
  def apply(context: ActorContext): TypedActorFactory = ContextualTypedActorFactory(apply(context.system), context)

  /**
   * Returns a contextual TypedActorFactory of this extension, this means that any TypedActors created by this TypedActorExtension
   * will be children to the specified context, this allows for creating hierarchies of TypedActors.
   * Do _not_ let this instance escape the TypedActor since that will not be thread-safe.
   *
   * Java API
   */
  def get(context: ActorContext): TypedActorFactory = apply(context)

  /**
   * This class represents a Method call, and has a reference to the Method to be called and the parameters to supply
   * It's sent to the ActorRef backing the TypedActor and can be serialized and deserialized
   */
  case class MethodCall(method: Method, parameters: Array[AnyRef]) {

    def isOneWay = method.getReturnType == java.lang.Void.TYPE
    def returnsFuture_? = classOf[Future[_]].isAssignableFrom(method.getReturnType)
    def returnsJOption_? = classOf[akka.japi.Option[_]].isAssignableFrom(method.getReturnType)
    def returnsOption_? = classOf[scala.Option[_]].isAssignableFrom(method.getReturnType)

    /**
     * Invokes the Method on the supplied instance
     *
     * @throws the underlying exception if there's an InvocationTargetException thrown on the invocation
     */
    def apply(instance: AnyRef): AnyRef = try {
      parameters match {
        case null                     ⇒ method.invoke(instance)
        case args if args.length == 0 ⇒ method.invoke(instance)
        case args                     ⇒ method.invoke(instance, args: _*)
      }
    } catch { case i: InvocationTargetException ⇒ throw i.getTargetException }

    private def writeReplace(): AnyRef = parameters match {
      case null                 ⇒ SerializedMethodCall(method.getDeclaringClass, method.getName, method.getParameterTypes, null)
      case ps if ps.length == 0 ⇒ SerializedMethodCall(method.getDeclaringClass, method.getName, method.getParameterTypes, Array())
      case ps ⇒
        val serialization = SerializationExtension(akka.serialization.JavaSerializer.currentSystem.value)
        val serializedParameters = Array.ofDim[(Int, Class[_], Array[Byte])](ps.length)
        for (i ← 0 until ps.length) {
          val p = ps(i)
          val s = serialization.findSerializerFor(p)
          val m = if (s.includeManifest) p.getClass else null
          serializedParameters(i) = (s.identifier, m, s toBinary parameters(i)) //Mutable for the sake of sanity
        }

        SerializedMethodCall(method.getDeclaringClass, method.getName, method.getParameterTypes, serializedParameters)
    }
  }

  /**
   * Represents the serialized form of a MethodCall, uses readResolve and writeReplace to marshall the call
   */
  @deprecated("Will become private[akka] in 2.1, this is not user-api", "2.0.2")
  case class SerializedMethodCall(ownerType: Class[_], methodName: String, parameterTypes: Array[Class[_]], serializedParameters: Array[(Int, Class[_], Array[Byte])]) {

    //TODO implement writeObject and readObject to serialize
    //TODO Possible optimization is to special encode the parameter-types to conserve space
    private def readResolve(): AnyRef = {
      val system = akka.serialization.JavaSerializer.currentSystem.value
      if (system eq null) throw new IllegalStateException(
        "Trying to deserialize a SerializedMethodCall without an ActorSystem in scope." +
          " Use akka.serialization.Serialization.currentSystem.withValue(system) { ... }")
      val serialization = SerializationExtension(system)
      MethodCall(ownerType.getDeclaredMethod(methodName, parameterTypes: _*), serializedParameters match {
        case null               ⇒ null
        case a if a.length == 0 ⇒ Array[AnyRef]()
        case a ⇒
          val deserializedParameters: Array[AnyRef] = Array.ofDim[AnyRef](a.length) //Mutable for the sake of sanity
          for (i ← 0 until a.length) {
            val (sId, manifest, bytes) = a(i)
            deserializedParameters(i) =
              serialization.serializerByIdentity(sId).fromBinary(bytes, Option(manifest))
          }

          deserializedParameters
      })
    }
  }

  private val selfReference = new ThreadLocal[AnyRef]
  private val currentContext = new ThreadLocal[ActorContext]

  /**
   * Returns the reference to the proxy when called inside a method call in a TypedActor
   *
   * Example:
   * <p/>
   * class FooImpl extends Foo {
   *   def doFoo {
   *     val myself = TypedActor.self[Foo]
   *   }
   * }
   *
   * Useful when you want to send a reference to this TypedActor to someone else.
   *
   * NEVER EXPOSE "this" to someone else, always use "self[TypeOfInterface(s)]"
   *
   * @throws IllegalStateException if called outside of the scope of a method on this TypedActor
   * @throws ClassCastException if the supplied type T isn't the type of the proxy associated with this TypedActor
   */
  def self[T <: AnyRef] = selfReference.get.asInstanceOf[T] match {
    case null ⇒ throw new IllegalStateException("Calling TypedActor.self outside of a TypedActor implementation method!")
    case some ⇒ some
  }

  /**
   * Returns the ActorContext (for a TypedActor) when inside a method call in a TypedActor.
   */
  def context: ActorContext = currentContext.get match {
    case null ⇒ throw new IllegalStateException("Calling TypedActor.context outside of a TypedActor implementation method!")
    case some ⇒ some
  }

  /**
   * Returns the default dispatcher (for a TypedActor) when inside a method call in a TypedActor.
   */
  implicit def dispatcher = context.dispatcher

  /**
   * Implementation of TypedActor as an Actor
   */
  private[akka] class TypedActor[R <: AnyRef, T <: R](val proxyVar: AtomVar[R], createInstance: ⇒ T) extends Actor {
    val me = try {
      TypedActor.selfReference set proxyVar.get
      TypedActor.currentContext set context
      createInstance
    } finally {
      TypedActor.selfReference set null
      TypedActor.currentContext set null
    }

    override def supervisorStrategy(): SupervisorStrategy = me match {
      case l: Supervisor ⇒ l.supervisorStrategy
      case _             ⇒ super.supervisorStrategy
    }

    override def preStart(): Unit = withContext {
      me match {
        case l: PreStart ⇒ l.preStart()
        case _           ⇒ super.preStart()
      }
    }

    override def postStop(): Unit = try {
      withContext {
        me match {
          case l: PostStop ⇒ l.postStop()
          case _           ⇒ super.postStop()
        }
      }
    } finally {
      TypedActor(context.system).invocationHandlerFor(proxyVar.get) match {
        case null ⇒
        case some ⇒
          some.actorVar.set(context.system.deadLetters) //Point it to the DLQ
          proxyVar.set(null.asInstanceOf[R])
      }
    }

    override def preRestart(reason: Throwable, message: Option[Any]): Unit = withContext {
      me match {
        case l: PreRestart ⇒ l.preRestart(reason, message)
        case _             ⇒ super.preRestart(reason, message)
      }
    }

    override def postRestart(reason: Throwable): Unit = withContext {
      me match {
        case l: PostRestart ⇒ l.postRestart(reason)
        case _              ⇒ super.postRestart(reason)
      }
    }

    protected def withContext[T](unitOfWork: ⇒ T): T = {
      TypedActor.selfReference set proxyVar.get
      TypedActor.currentContext set context
      try unitOfWork finally {
        TypedActor.selfReference set null
        TypedActor.currentContext set null
      }
    }

    def receive = {
      case m: MethodCall ⇒ withContext {
        if (m.isOneWay) m(me)
        else {
          try {
            if (m.returnsFuture_?) {
              val s = sender
              m(me).asInstanceOf[Future[Any]] onComplete {
                case Left(f)  ⇒ s ! Status.Failure(f)
                case Right(r) ⇒ s ! r
              }
            } else {
              sender ! m(me)
            }
          } catch {
            case NonFatal(e) ⇒
              sender ! Status.Failure(e)
              throw e
          }
        }
      }

      case msg if me.isInstanceOf[Receiver] ⇒ withContext {
        me.asInstanceOf[Receiver].onReceive(msg, sender)
      }
    }
  }

  /**
   * Mix this into your TypedActor to be able to define supervisor strategy
   */
  trait Supervisor {
    /**
     * User overridable definition the strategy to use for supervising
     * child actors.
     */
    def supervisorStrategy(): SupervisorStrategy
  }

  /**
   * Mix this into your TypedActor to be able to intercept Terminated messages
   */
  trait Receiver {
    def onReceive(message: Any, sender: ActorRef): Unit
  }

  /**
   * Mix this into your TypedActor to be able to hook into its lifecycle
   */
  trait PreStart {
    /**
     * User overridable callback.
     * <p/>
     * Is called when an Actor is started by invoking 'actor'.
     */
    def preStart(): Unit
  }

  /**
   * Mix this into your TypedActor to be able to hook into its lifecycle
   */
  trait PostStop {
    /**
     * User overridable callback.
     * <p/>
     * Is called when 'actor.stop()' is invoked.
     */
    def postStop(): Unit
  }

  /**
   * Mix this into your TypedActor to be able to hook into its lifecycle
   */
  trait PreRestart {
    /**
     * User overridable callback: '''By default it disposes of all children and then calls `postStop()`.'''
     * @param reason the Throwable that caused the restart to happen
     * @param message optionally the current message the actor processed when failing, if applicable
     * <p/>
     * Is called on a crashed Actor right BEFORE it is restarted to allow clean
     * up of resources before Actor is terminated.
     * By default it terminates all children and calls postStop()
     */
    def preRestart(reason: Throwable, message: Option[Any]): Unit
  }

  trait PostRestart {
    /**
     * User overridable callback: By default it calls `preStart()`.
     * @param reason the Throwable that caused the restart to happen
     * <p/>
     * Is called right AFTER restart on the newly created Actor to allow reinitialization after an Actor crash.
     */
    def postRestart(reason: Throwable): Unit
  }

  private[akka] class TypedActorInvocationHandler(val extension: TypedActorExtension, val actorVar: AtomVar[ActorRef], val timeout: Timeout) extends InvocationHandler {
    def actor = actorVar.get

    @throws(classOf[Throwable])
    def invoke(proxy: AnyRef, method: Method, args: Array[AnyRef]): AnyRef = method.getName match {
      case "toString" ⇒ actor.toString
      case "equals"   ⇒ (args.length == 1 && (proxy eq args(0)) || actor == extension.getActorRefFor(args(0))).asInstanceOf[AnyRef] //Force boxing of the boolean
      case "hashCode" ⇒ actor.hashCode.asInstanceOf[AnyRef]
      case _ ⇒
        import akka.pattern.ask
        MethodCall(method, args) match {
          case m if m.isOneWay        ⇒ actor ! m; null //Null return value
          case m if m.returnsFuture_? ⇒ ask(actor, m)(timeout)
          case m if m.returnsJOption_? || m.returnsOption_? ⇒
            val f = ask(actor, m)(timeout)
            (try { Await.ready(f, timeout.duration).value } catch { case _: TimeoutException ⇒ None }) match {
              case None | Some(Right(null))     ⇒ if (m.returnsJOption_?) JOption.none[Any] else None
              case Some(Right(joption: AnyRef)) ⇒ joption
              case Some(Left(ex))               ⇒ throw ex
            }
          case m ⇒ Await.result(ask(actor, m)(timeout), timeout.duration).asInstanceOf[AnyRef]
        }
    }
  }
}

/**
 * TypedProps is a TypedActor configuration object, that is thread safe and fully sharable.
 * It's used in TypedActorFactory.typedActorOf to configure a TypedActor instance.
 */
object TypedProps {

  val defaultDispatcherId: String = Dispatchers.DefaultDispatcherId
  val defaultTimeout: Option[Timeout] = None
  val defaultLoader: Option[ClassLoader] = None

  /**
   * @return a sequence of interfaces that the specified class implements,
   * or a sequence containing only itself, if itself is an interface.
   */
  def extractInterfaces(clazz: Class[_]): Seq[Class[_]] =
    if (clazz.isInterface) Seq[Class[_]](clazz) else clazz.getInterfaces.toList

  /**
   * Uses the supplied class as the factory for the TypedActor implementation,
   * proxying all the interfaces it implements.
   *
   * Scala API
   */
  def apply[T <: AnyRef](implementation: Class[T]): TypedProps[T] =
    new TypedProps[T](implementation)

  /**
   * Uses the supplied class as the factory for the TypedActor implementation,
   * and that has the specified interface,
   * or if the interface class is not an interface, all the interfaces it implements,
   * appended in the sequence of interfaces.
   *
   * Scala API
   */
  def apply[T <: AnyRef](interface: Class[_ >: T], implementation: Class[T]): TypedProps[T] =
    new TypedProps[T](extractInterfaces(interface), () ⇒ implementation.newInstance())

  /**
   * Uses the supplied thunk as the factory for the TypedActor implementation,
   * and that has the specified interface,
   * or if the interface class is not an interface, all the interfaces it implements,
   * appended in the sequence of interfaces.
   *
   * Scala API
   */
  def apply[T <: AnyRef](interface: Class[_ >: T], creator: ⇒ T): TypedProps[T] =
    new TypedProps[T](extractInterfaces(interface), () ⇒ creator)

  /**
   * Uses the supplied class as the factory for the TypedActor implementation,
   * proxying all the interfaces it implements.
   *
   * Scala API
   */
  def apply[T <: AnyRef: ClassManifest](): TypedProps[T] =
    new TypedProps[T](implicitly[ClassManifest[T]].erasure.asInstanceOf[Class[T]])
}

/**
 * TypedProps is a TypedActor configuration object, that is thread safe and fully sharable.
 * It's used in TypedActorFactory.typedActorOf to configure a TypedActor instance.
 */
//TODO add @SerialVersionUID(1L) when SI-4804 is fixed
case class TypedProps[T <: AnyRef] protected[TypedProps] (
  interfaces: Seq[Class[_]],
  creator: () ⇒ T,
  dispatcher: String = TypedProps.defaultDispatcherId,
  deploy: Deploy = Props.defaultDeploy,
  timeout: Option[Timeout] = TypedProps.defaultTimeout,
  loader: Option[ClassLoader] = TypedProps.defaultLoader) {

  /**
   * Uses the supplied class as the factory for the TypedActor implementation,
   * and that has the specified interface,
   * or if the interface class is not an interface, all the interfaces it implements,
   * appended in the sequence of interfaces.
   */
  def this(implementation: Class[T]) =
    this(interfaces = TypedProps.extractInterfaces(implementation),
      creator = () ⇒ implementation.newInstance())

  /**
   * Uses the supplied Creator as the factory for the TypedActor implementation,
   * and that has the specified interface,
   * or if the interface class is not an interface, all the interfaces it implements,
   * appended in the sequence of interfaces.
   *
   * Java API.
   */
  def this(interface: Class[_ >: T], implementation: Creator[T]) =
    this(interfaces = TypedProps.extractInterfaces(interface),
      creator = () ⇒ implementation.create())

  /**
   * Uses the supplied class as the factory for the TypedActor implementation,
   * and that has the specified interface,
   * or if the interface class is not an interface, all the interfaces it implements,
   * appended in the sequence of interfaces.
   *
   * Java API.
   */
  def this(interface: Class[_ >: T], implementation: Class[T]) =
    this(interfaces = TypedProps.extractInterfaces(interface),
      creator = () ⇒ implementation.newInstance())

  /**
   * Returns a new TypedProps with the specified dispatcher set.
   */
  def withDispatcher(d: String): TypedProps[T] = copy(dispatcher = d)

  /**
   * Returns a new TypedProps with the specified deployment configuration.
   */
  def withDeploy(d: Deploy): TypedProps[T] = copy(deploy = d)

  /**
   * @return a new TypedProps that will use the specified ClassLoader to create its proxy class in
   * If loader is null, it will use the bootstrap classloader.
   *
   * Java API
   */
  def withLoader(loader: ClassLoader): TypedProps[T] = withLoader(Option(loader))

  /**
   * @return a new TypedProps that will use the specified ClassLoader to create its proxy class in
   * If loader is null, it will use the bootstrap classloader.
   *
   * Scala API
   */
  def withLoader(loader: Option[ClassLoader]): TypedProps[T] = this.copy(loader = loader)

  /**
   * @return a new TypedProps that will use the specified Timeout for its non-void-returning methods,
   * if null is specified, it will use the default timeout as specified in the configuration.
   *
   * Java API
   */
  def withTimeout(timeout: Timeout): TypedProps[T] = this.copy(timeout = Option(timeout))

  /**
   * @return a new TypedProps that will use the specified Timeout for its non-void-returning methods,
   * if None is specified, it will use the default timeout as specified in the configuration.
   *
   * Scala API
   */
  def withTimeout(timeout: Option[Timeout]): TypedProps[T] = this.copy(timeout = timeout)

  /**
   * Returns a new TypedProps that has the specified interface,
   * or if the interface class is not an interface, all the interfaces it implements,
   * appended in the sequence of interfaces.
   */
  def withInterface(interface: Class[_ >: T]): TypedProps[T] =
    this.copy(interfaces = interfaces ++ TypedProps.extractInterfaces(interface))

  /**
   * Returns a new TypedProps without the specified interface,
   * or if the interface class is not an interface, all the interfaces it implements.
   */
  def withoutInterface(interface: Class[_ >: T]): TypedProps[T] =
    this.copy(interfaces = interfaces diff TypedProps.extractInterfaces(interface))

  import akka.actor.{ Props ⇒ ActorProps }
  def actorProps(): ActorProps =
    if (dispatcher == ActorProps().dispatcher) ActorProps()
    else ActorProps(dispatcher = dispatcher)
}

case class ContextualTypedActorFactory(typedActor: TypedActorExtension, actorFactory: ActorContext) extends TypedActorFactory {
  override def getActorRefFor(proxy: AnyRef): ActorRef = typedActor.getActorRefFor(proxy)
  override def isTypedActor(proxyOrNot: AnyRef): Boolean = typedActor.isTypedActor(proxyOrNot)
}

class TypedActorExtension(system: ExtendedActorSystem) extends TypedActorFactory with Extension {
  import TypedActor._ //Import the goodies from the companion object
  protected def actorFactory: ActorRefFactory = system
  protected def typedActor = this

  val serialization = SerializationExtension(system)
  val settings = system.settings

  /**
   * Default timeout for typed actor methods with non-void return type
   */
  final val DefaultReturnTimeout = Timeout(Duration(settings.config.getMilliseconds("akka.actor.typed.timeout"), MILLISECONDS))

  /**
   * Retrieves the underlying ActorRef for the supplied TypedActor proxy, or null if none found
   */
  def getActorRefFor(proxy: AnyRef): ActorRef = invocationHandlerFor(proxy) match {
    case null    ⇒ null
    case handler ⇒ handler.actor
  }

  /**
   * Returns wether the supplied AnyRef is a TypedActor proxy or not
   */
  def isTypedActor(proxyOrNot: AnyRef): Boolean = invocationHandlerFor(proxyOrNot) ne null

  // Private API

  private[akka] def createActorRefProxy[R <: AnyRef, T <: R](props: TypedProps[T], proxyVar: AtomVar[R], actorRef: ⇒ ActorRef): R = {
    //Warning, do not change order of the following statements, it's some elaborate chicken-n-egg handling
    val actorVar = new AtomVar[ActorRef](null)
    val classLoader: ClassLoader = if (props.loader.nonEmpty) props.loader.get else props.interfaces.headOption.map(_.getClassLoader).orNull //If we have no loader, we arbitrarily take the loader of the first interface
    val proxy = Proxy.newProxyInstance(
      classLoader,
      props.interfaces.toArray,
      new TypedActorInvocationHandler(
        this,
        actorVar,
        if (props.timeout.isDefined) props.timeout.get else DefaultReturnTimeout)).asInstanceOf[R]

    proxyVar match {
      case null ⇒
        actorVar.set(actorRef)
        proxy
      case _ ⇒
        proxyVar.set(proxy) // Chicken and egg situation we needed to solve, set the proxy so that we can set the self-reference inside each receive
        actorVar.set(actorRef) //Make sure the InvocationHandler gets ahold of the actor reference, this is not a problem since the proxy hasn't escaped this method yet
        proxyVar.get
    }
  }

  private[akka] def invocationHandlerFor(typedActor_? : AnyRef): TypedActorInvocationHandler =
    if ((typedActor_? ne null) && Proxy.isProxyClass(typedActor_?.getClass)) typedActor_? match {
      case null ⇒ null
      case other ⇒ Proxy.getInvocationHandler(other) match {
        case null                                 ⇒ null
        case handler: TypedActorInvocationHandler ⇒ handler
        case _                                    ⇒ null
      }
    }
    else null
}
