package akka.actor

/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

import akka.japi.{ Creator, Option ⇒ JOption }
import java.lang.reflect.{ InvocationTargetException, Method, InvocationHandler, Proxy }
import akka.util.{ Duration, Timeout }
import java.util.concurrent.atomic.{ AtomicReference ⇒ AtomVar }
import akka.serialization.{ Serializer, Serialization }
import akka.dispatch._
import akka.serialization.SerializationExtension
import java.util.concurrent.TimeoutException

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
   * Creates a new TypedActor proxy using the supplied Props,
   * the interfaces usable by the returned proxy is the suppli  ed interface class (if the class represents an interface) or
   * all interfaces (Class.getInterfaces) if it's not an interface class
   *
   * Java API
   */
  def typedActorOf[R <: AnyRef, T <: R](interface: Class[R], impl: Class[T], props: Props): R =
    typedActor.createProxyAndTypedActor(actorFactory, interface, impl.newInstance, props, None, interface.getClassLoader)

  /**
   * Creates a new TypedActor proxy using the supplied Props,
   * the interfaces usable by the returned proxy is the supplied interface class (if the class represents an interface) or
   * all interfaces (Class.getInterfaces) if it's not an interface class
   *
   * Java API
   */
  def typedActorOf[R <: AnyRef, T <: R](interface: Class[R], impl: Class[T], props: Props, name: String): R =
    typedActor.createProxyAndTypedActor(actorFactory, interface, impl.newInstance, props, Some(name), interface.getClassLoader)

  /**
   * Creates a new TypedActor proxy using the supplied Props,
   * the interfaces usable by the returned proxy is the supplied interface class (if the class represents an interface) or
   * all interfaces (Class.getInterfaces) if it's not an interface class
   *
   * Java API
   */
  def typedActorOf[R <: AnyRef, T <: R](interface: Class[R], impl: Creator[T], props: Props): R =
    typedActor.createProxyAndTypedActor(actorFactory, interface, impl.create, props, None, interface.getClassLoader)

  /**
   * Creates a new TypedActor proxy using the supplied Props,
   * the interfaces usable by the returned proxy is the supplied interface class (if the class represents an interface) or
   * all interfaces (Class.getInterfaces) if it's not an interface class
   *
   * Java API
   */
  def typedActorOf[R <: AnyRef, T <: R](interface: Class[R], impl: Creator[T], props: Props, name: String): R =
    typedActor.createProxyAndTypedActor(actorFactory, interface, impl.create, props, Some(name), interface.getClassLoader)

  /**
   * Creates a new TypedActor proxy using the supplied Props,
   * the interfaces usable by the returned proxy is the supplied interface class (if the class represents an interface) or
   * all interfaces (Class.getInterfaces) if it's not an interface class
   *
   * Scala API
   */
  def typedActorOf[R <: AnyRef, T <: R](interface: Class[R], impl: ⇒ T, props: Props, name: String): R =
    typedActor.createProxyAndTypedActor(actorFactory, interface, impl, props, Some(name), interface.getClassLoader)

  /**
   * Creates a new TypedActor proxy using the supplied Props,
   * the interfaces usable by the returned proxy is the supplied implementation class' interfaces (Class.getInterfaces)
   *
   * Scala API
   */
  def typedActorOf[R <: AnyRef, T <: R: ClassManifest](props: Props = Props(), name: String = null): R = {
    val clazz = implicitly[ClassManifest[T]].erasure.asInstanceOf[Class[T]]
    typedActor.createProxyAndTypedActor(actorFactory, clazz, clazz.newInstance, props, Option(name), clazz.getClassLoader)
  }

  /**
   * Creates a proxy given the supplied Props, this is not a TypedActor, so you'll need to implement the MethodCall handling yourself,
   * to create TypedActor proxies, use typedActorOf
   */
  def createProxy[R <: AnyRef](constructor: ⇒ Actor, props: Props = Props(), name: String = null, loader: ClassLoader = null)(implicit m: Manifest[R]): R =
    typedActor.createProxy[R](actorFactory, typedActor.extractInterfaces(m.erasure), (ref: AtomVar[R]) ⇒ constructor, props, Option(name), if (loader eq null) m.erasure.getClassLoader else loader)

  /**
   * Creates a proxy given the supplied Props, this is not a TypedActor, so you'll need to implement the MethodCall handling yourself,
   * to create TypedActor proxies, use typedActorOf
   */
  def createProxy[R <: AnyRef](interfaces: Array[Class[_]], constructor: Creator[Actor], props: Props, loader: ClassLoader): R =
    typedActor.createProxy(actorFactory, interfaces, (ref: AtomVar[R]) ⇒ constructor.create, props, None, loader)

  /**
   * Creates a proxy given the supplied Props, this is not a TypedActor, so you'll need to implement the MethodCall handling yourself,
   * to create TypedActor proxies, use typedActorOf
   */
  def createProxy[R <: AnyRef](interfaces: Array[Class[_]], constructor: Creator[Actor], props: Props, name: String, loader: ClassLoader): R =
    typedActor.createProxy(actorFactory, interfaces, (ref: AtomVar[R]) ⇒ constructor.create, props, Some(name), loader)

  /**
   * Creates a proxy given the supplied Props, this is not a TypedActor, so you'll need to implement the MethodCall handling yourself,
   * to create TypedActor proxies, use typedActorOf
   */
  def createProxy[R <: AnyRef](interfaces: Array[Class[_]], constructor: ⇒ Actor, props: Props, loader: ClassLoader): R =
    typedActor.createProxy[R](actorFactory, interfaces, (ref: AtomVar[R]) ⇒ constructor, props, None, loader)

  /**
   * Creates a proxy given the supplied Props, this is not a TypedActor, so you'll need to implement the MethodCall handling yourself,
   * to create TypedActor proxies, use typedActorOf
   */
  def createProxy[R <: AnyRef](interfaces: Array[Class[_]], constructor: ⇒ Actor, props: Props, name: String, loader: ClassLoader): R =
    typedActor.createProxy[R](actorFactory, interfaces, (ref: AtomVar[R]) ⇒ constructor, props, Some(name), loader)

}

object TypedActor extends ExtensionId[TypedActorExtension] with ExtensionIdProvider {
  override def get(system: ActorSystem): TypedActorExtension = super.get(system)

  def lookup() = this
  def createExtension(system: ActorSystemImpl): TypedActorExtension = new TypedActorExtension(system)

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
      case null                 ⇒ SerializedMethodCall(method.getDeclaringClass, method.getName, method.getParameterTypes, null, null)
      case ps if ps.length == 0 ⇒ SerializedMethodCall(method.getDeclaringClass, method.getName, method.getParameterTypes, Array[Int](), Array[Array[Byte]]())
      case ps ⇒
        val serializers: Array[Serializer] = ps map SerializationExtension(Serialization.currentSystem.value).findSerializerFor
        val serializedParameters: Array[Array[Byte]] = Array.ofDim[Array[Byte]](serializers.length)
        for (i ← 0 until serializers.length)
          serializedParameters(i) = serializers(i) toBinary parameters(i) //Mutable for the sake of sanity

        SerializedMethodCall(method.getDeclaringClass, method.getName, method.getParameterTypes, serializers.map(_.identifier), serializedParameters)
    }
  }

  /**
   * Represents the serialized form of a MethodCall, uses readResolve and writeReplace to marshall the call
   */
  case class SerializedMethodCall(ownerType: Class[_], methodName: String, parameterTypes: Array[Class[_]], serializerIdentifiers: Array[Int], serializedParameters: Array[Array[Byte]]) {

    //TODO implement writeObject and readObject to serialize
    //TODO Possible optimization is to special encode the parameter-types to conserve space
    private def readResolve(): AnyRef = {
      val system = akka.serialization.Serialization.currentSystem.value
      if (system eq null) throw new IllegalStateException(
        "Trying to deserialize a SerializedMethodCall without an ActorSystem in scope." +
          " Use akka.serialization.Serialization.currentSystem.withValue(system) { ... }")
      val serialization = SerializationExtension(system)
      MethodCall(ownerType.getDeclaredMethod(methodName, parameterTypes: _*), serializedParameters match {
        case null               ⇒ null
        case a if a.length == 0 ⇒ Array[AnyRef]()
        case a ⇒
          val deserializedParameters: Array[AnyRef] = Array.ofDim[AnyRef](a.length) //Mutable for the sake of sanity
          for (i ← 0 until a.length)
            deserializedParameters(i) = serialization.serializerByIdentity(serializerIdentifiers(i)).fromBinary(serializedParameters(i))

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
  def context = currentContext.get match {
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

    override def preStart(): Unit = me match {
      case l: PreStart ⇒ l.preStart()
      case _           ⇒ super.preStart()
    }

    override def postStop(): Unit = try {
      me match {
        case l: PostStop ⇒ l.postStop()
        case _           ⇒ super.postStop()
      }
    } finally {
      TypedActor(context.system).invocationHandlerFor(proxyVar.get) match {
        case null ⇒
        case some ⇒
          some.actorVar.set(context.system.deadLetters) //Point it to the DLQ
          proxyVar.set(null.asInstanceOf[R])
      }
    }

    override def preRestart(reason: Throwable, message: Option[Any]): Unit = me match {
      case l: PreRestart ⇒ l.preRestart(reason, message)
      case _             ⇒ super.preRestart(reason, message)
    }

    override def postRestart(reason: Throwable): Unit = me match {
      case l: PostRestart ⇒ l.postRestart(reason)
      case _              ⇒ super.postRestart(reason)
    }

    def receive = {
      case m: MethodCall ⇒
        TypedActor.selfReference set proxyVar.get
        TypedActor.currentContext set context
        try {
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
              case t: Throwable ⇒ sender ! Status.Failure(t); throw t
            }
          }
        } finally {
          TypedActor.selfReference set null
          TypedActor.currentContext set null
        }
    }
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
    def preStart(): Unit = ()
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
    def postStop(): Unit = ()
  }

  /**
   * Mix this into your TypedActor to be able to hook into its lifecycle
   */
  trait PreRestart {
    /**
     * User overridable callback.
     * <p/>
     * Is called on a crashed Actor right BEFORE it is restarted to allow clean
     * up of resources before Actor is terminated.
     * By default it calls postStop()
     */
    def preRestart(reason: Throwable, message: Option[Any]): Unit = ()
  }

  trait PostRestart {
    /**
     * User overridable callback.
     * <p/>
     * Is called right AFTER restart on the newly created Actor to allow reinitialization after an Actor crash.
     * By default it calls preStart()
     */
    def postRestart(reason: Throwable): Unit = ()
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
          case m if m.returnsFuture_? ⇒ actor.?(m, timeout)
          case m if m.returnsJOption_? || m.returnsOption_? ⇒
            val f = actor.?(m, timeout)
            (try { Await.ready(f, timeout.duration).value } catch { case _: TimeoutException ⇒ None }) match {
              case None | Some(Right(null))     ⇒ if (m.returnsJOption_?) JOption.none[Any] else None
              case Some(Right(joption: AnyRef)) ⇒ joption
              case Some(Left(ex))               ⇒ throw ex
            }
          case m ⇒ Await.result(actor.?(m, timeout), timeout.duration).asInstanceOf[AnyRef]
        }
    }
  }
}

case class ContextualTypedActorFactory(typedActor: TypedActorExtension, actorFactory: ActorContext) extends TypedActorFactory {
  override def getActorRefFor(proxy: AnyRef): ActorRef = typedActor.getActorRefFor(proxy)
  override def isTypedActor(proxyOrNot: AnyRef): Boolean = typedActor.isTypedActor(proxyOrNot)
}

class TypedActorExtension(system: ActorSystemImpl) extends TypedActorFactory with Extension {
  import TypedActor._ //Import the goodies from the companion object
  protected def actorFactory: ActorRefFactory = system
  protected def typedActor = this

  val serialization = SerializationExtension(system)
  val settings = system.settings

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

  private[akka] def createProxy[R <: AnyRef](supervisor: ActorRefFactory, interfaces: Array[Class[_]], constructor: (AtomVar[R]) ⇒ Actor, props: Props, name: Option[String], loader: ClassLoader): R = {
    val proxyVar = new AtomVar[R]
    configureAndProxyLocalActorRef[R](supervisor, interfaces, proxyVar, props.withCreator(constructor(proxyVar)), name, loader)
  }

  private[akka] def createProxyAndTypedActor[R <: AnyRef, T <: R](supervisor: ActorRefFactory, interface: Class[_], constructor: ⇒ T, props: Props, name: Option[String], loader: ClassLoader): R =
    createProxy[R](supervisor, extractInterfaces(interface), (ref: AtomVar[R]) ⇒ new TypedActor[R, T](ref, constructor), props, name, loader)

  private[akka] def configureAndProxyLocalActorRef[T <: AnyRef](supervisor: ActorRefFactory, interfaces: Array[Class[_]], proxyVar: AtomVar[T], props: Props, name: Option[String], loader: ClassLoader): T = {
    //Warning, do not change order of the following statements, it's some elaborate chicken-n-egg handling
    val actorVar = new AtomVar[ActorRef](null)
    val timeout = props.timeout match {
      case Props.`defaultTimeout` ⇒ settings.ActorTimeout
      case x                      ⇒ x
    }
    val proxy: T = Proxy.newProxyInstance(loader, interfaces, new TypedActorInvocationHandler(this, actorVar, timeout)).asInstanceOf[T]
    proxyVar.set(proxy) // Chicken and egg situation we needed to solve, set the proxy so that we can set the self-reference inside each receive
    val ref = if (name.isDefined) supervisor.actorOf(props, name.get) else supervisor.actorOf(props)
    actorVar.set(ref) //Make sure the InvocationHandler gets ahold of the actor reference, this is not a problem since the proxy hasn't escaped this method yet
    proxyVar.get
  }

  private[akka] def extractInterfaces(clazz: Class[_]): Array[Class[_]] = if (clazz.isInterface) Array[Class[_]](clazz) else clazz.getInterfaces

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
