package akka.actor

/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

import akka.japi.{ Creator, Option ⇒ JOption }
import akka.actor.Actor._
import java.lang.reflect.{ InvocationTargetException, Method, InvocationHandler, Proxy }
import akka.util.{ Duration }
import java.util.concurrent.atomic.{ AtomicReference ⇒ AtomVar }
import akka.serialization.{ Serializer, Serialization }
import akka.dispatch._

//TODO Document this class, not only in Scaladoc, but also in a dedicated typed-actor.rst, for both java and scala
/**
 * A TypedActor in Akka is an implementation of the Active Objects Pattern, i.e. an object with asynchronous method dispatch
 *
 * It consists of 2 parts:
 *   The Interface
 *   The Implementation
 *
 *   Given a combination of Interface and Implementation, a JDK Dynamic Proxy object with the Interface will be returned
 *
 *   The semantics is as follows,
 *     any methods in the Interface that returns Unit/void will use fire-and-forget semantics (same as Actor !)
 *     any methods in the Interface that returns Option/JOption will use ask + block-with-timeout-return-none-if-timeout semantics
 *     any methods in the Interface that returns anything else will use ask + block-with-timeout-throw-if-timeout semantics
 *
 *  TypedActors needs, just like Actors, to be Stopped when they are no longer needed, use TypedActor.stop(proxy)
 */
object TypedActor {
  private val selfReference = new ThreadLocal[AnyRef]

  /**
   * Returns the reference to the proxy when called inside a method call in a TypedActor
   *
   * Example:
   * <p/>
   * class FooImpl extends Foo {
   *   def doFoo {
   *     val myself = self[Foo]
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
      parameters match { //TODO: We do not yet obey Actor.SERIALIZE_MESSAGES
        case null                     ⇒ method.invoke(instance)
        case args if args.length == 0 ⇒ method.invoke(instance)
        case args                     ⇒ method.invoke(instance, args: _*)
      }
    } catch { case i: InvocationTargetException ⇒ throw i.getTargetException }

    private def writeReplace(): AnyRef = parameters match {
      case null                 ⇒ SerializedMethodCall(method.getDeclaringClass, method.getName, method.getParameterTypes, null, null)
      case ps if ps.length == 0 ⇒ SerializedMethodCall(method.getDeclaringClass, method.getName, method.getParameterTypes, Array[Serializer.Identifier](), Array[Array[Byte]]())
      case ps ⇒
        val serializers: Array[Serializer] = ps map Serialization.findSerializerFor
        val serializedParameters: Array[Array[Byte]] = Array.ofDim[Array[Byte]](serializers.length)
        for (i ← 0 until serializers.length)
          serializedParameters(i) = serializers(i) toBinary parameters(i) //Mutable for the sake of sanity

        SerializedMethodCall(method.getDeclaringClass, method.getName, method.getParameterTypes, serializers.map(_.identifier), serializedParameters)
    }
  }

  /**
   * Represents the serialized form of a MethodCall, uses readResolve and writeReplace to marshall the call
   */
  case class SerializedMethodCall(ownerType: Class[_], methodName: String, parameterTypes: Array[Class[_]], serializerIdentifiers: Array[Serializer.Identifier], serializedParameters: Array[Array[Byte]]) {
    //TODO implement writeObject and readObject to serialize
    //TODO Possible optimization is to special encode the parameter-types to conserve space
    private def readResolve(): AnyRef = {
      MethodCall(ownerType.getDeclaredMethod(methodName, parameterTypes: _*), serializedParameters match {
        case null               ⇒ null
        case a if a.length == 0 ⇒ Array[AnyRef]()
        case a ⇒
          val deserializedParameters: Array[AnyRef] = Array.ofDim[AnyRef](a.length) //Mutable for the sake of sanity
          for (i ← 0 until a.length)
            deserializedParameters(i) = Serialization.serializerByIdentity(serializerIdentifiers(i)).fromBinary(serializedParameters(i))

          deserializedParameters
      })
    }
  }

  /**
   * Creates a new TypedActor proxy using the supplied Props,
   * the interfaces usable by the returned proxy is the supplied interface class (if the class represents an interface) or
   * all interfaces (Class.getInterfaces) if it's not an interface class
   */
  def typedActorOf[R <: AnyRef, T <: R](interface: Class[R], impl: Class[T], props: Props): R =
    createProxyAndTypedActor(interface, impl.newInstance, props, newUuid().toString, interface.getClassLoader)

  /**
   * Creates a new TypedActor proxy using the supplied Props,
   * the interfaces usable by the returned proxy is the supplied interface class (if the class represents an interface) or
   * all interfaces (Class.getInterfaces) if it's not an interface class
   */
  def typedActorOf[R <: AnyRef, T <: R](interface: Class[R], impl: Class[T], props: Props, loader: ClassLoader): R =
    createProxyAndTypedActor(interface, impl.newInstance, props, newUuid().toString, loader)

  /**
   * Creates a new TypedActor proxy using the supplied Props,
   * the interfaces usable by the returned proxy is the supplied interface class (if the class represents an interface) or
   * all interfaces (Class.getInterfaces) if it's not an interface class
   */
  def typedActorOf[R <: AnyRef, T <: R](interface: Class[R], impl: Class[T], props: Props, address: String): R =
    createProxyAndTypedActor(interface, impl.newInstance, props, address, interface.getClassLoader)

  /**
   * Creates a new TypedActor proxy using the supplied Props,
   * the interfaces usable by the returned proxy is the supplied interface class (if the class represents an interface) or
   * all interfaces (Class.getInterfaces) if it's not an interface class
   */
  def typedActorOf[R <: AnyRef, T <: R](interface: Class[R], impl: Class[T], props: Props, address: String, loader: ClassLoader): R =
    createProxyAndTypedActor(interface, impl.newInstance, props, address, interface.getClassLoader)

  /**
   * Creates a new TypedActor proxy using the supplied Props,
   * the interfaces usable by the returned proxy is the supplied interface class (if the class represents an interface) or
   * all interfaces (Class.getInterfaces) if it's not an interface class
   */
  def typedActorOf[R <: AnyRef, T <: R](interface: Class[R], impl: Creator[T], props: Props): R =
    createProxyAndTypedActor(interface, impl.create(), props, newUuid().toString, interface.getClassLoader)

  /**
   * Creates a new TypedActor proxy using the supplied Props,
   * the interfaces usable by the returned proxy is the supplied interface class (if the class represents an interface) or
   * all interfaces (Class.getInterfaces) if it's not an interface class
   */
  def typedActorOf[R <: AnyRef, T <: R](interface: Class[R], impl: Creator[T], props: Props, loader: ClassLoader): R =
    createProxyAndTypedActor(interface, impl.create(), props, newUuid().toString, loader)

  /**
   * Creates a new TypedActor proxy using the supplied Props,
   * the interfaces usable by the returned proxy is the supplied interface class (if the class represents an interface) or
   * all interfaces (Class.getInterfaces) if it's not an interface class
   */
  def typedActorOf[R <: AnyRef, T <: R](interface: Class[R], impl: Creator[T], props: Props, address: String): R =
    createProxyAndTypedActor(interface, impl.create(), props, address, interface.getClassLoader)

  /**
   * Creates a new TypedActor proxy using the supplied Props,
   * the interfaces usable by the returned proxy is the supplied interface class (if the class represents an interface) or
   * all interfaces (Class.getInterfaces) if it's not an interface class
   */
  def typedActorOf[R <: AnyRef, T <: R](interface: Class[R], impl: Creator[T], props: Props, address: String, loader: ClassLoader): R =
    createProxyAndTypedActor(interface, impl.create(), props, address, loader)

  /**
   * Creates a new TypedActor proxy using the supplied Props,
   * the interfaces usable by the returned proxy is the supplied implementation class' interfaces (Class.getInterfaces)
   */
  def typedActorOf[R <: AnyRef, T <: R](impl: Class[T], props: Props, loader: ClassLoader): R =
    createProxyAndTypedActor(impl, impl.newInstance, props, newUuid().toString, loader)

  /**
   * Creates a new TypedActor proxy using the supplied Props,
   * the interfaces usable by the returned proxy is the supplied implementation class' interfaces (Class.getInterfaces)
   */
  def typedActorOf[R <: AnyRef, T <: R](impl: Class[T], props: Props, address: String, loader: ClassLoader): R =
    createProxyAndTypedActor(impl, impl.newInstance, props, address, loader)

  /**
   * Creates a new TypedActor proxy using the supplied Props,
   * the interfaces usable by the returned proxy is the supplied implementation class' interfaces (Class.getInterfaces)
   */
  def typedActorOf[R <: AnyRef, T <: R](props: Props = Props(), address: String = newUuid().toString, loader: ClassLoader = null)(implicit m: Manifest[T]): R = {
    val clazz = m.erasure.asInstanceOf[Class[T]]
    createProxyAndTypedActor(clazz, clazz.newInstance, props, address, if (loader eq null) clazz.getClassLoader else loader)
  }

  /**
   * Stops the underlying ActorRef for the supplied TypedActor proxy, if any, returns whether it could stop it or not
   */
  def stop(proxy: AnyRef): Boolean = getActorRefFor(proxy) match {
    case null ⇒ false
    case ref  ⇒ ref.stop; true
  }

  /**
   * Retrieves the underlying ActorRef for the supplied TypedActor proxy, or null if none found
   */
  def getActorRefFor(proxy: AnyRef): ActorRef = invocationHandlerFor(proxy) match {
    case null    ⇒ null
    case handler ⇒ handler.actor
  }

  /**
   * Returns whether the supplied AnyRef is a TypedActor proxy or not
   */
  def isTypedActor(proxyOrNot: AnyRef): Boolean = invocationHandlerFor(proxyOrNot) ne null

  /**
   * Creates a proxy given the supplied Props, this is not a TypedActor, so you'll need to implement the MethodCall handling yourself,
   * to create TypedActor proxies, use typedActorOf
   */
  def createProxy[R <: AnyRef](constructor: ⇒ Actor, props: Props = Props(), address: String = newUuid().toString, loader: ClassLoader = null)(implicit m: Manifest[R]): R =
    createProxy[R](extractInterfaces(m.erasure), (ref: AtomVar[R]) ⇒ constructor, props, address, if (loader eq null) m.erasure.getClassLoader else loader)

  /**
   * Creates a proxy given the supplied Props, this is not a TypedActor, so you'll need to implement the MethodCall handling yourself,
   * to create TypedActor proxies, use typedActorOf
   */
  def createProxy[R <: AnyRef](interfaces: Array[Class[_]], constructor: Creator[Actor], props: Props, address: String, loader: ClassLoader): R =
    createProxy(interfaces, (ref: AtomVar[R]) ⇒ constructor.create, props, address, loader)

  /**
   * Creates a proxy given the supplied Props, this is not a TypedActor, so you'll need to implement the MethodCall handling yourself,
   * to create TypedActor proxies, use typedActorOf
   */
  def createProxy[R <: AnyRef](interfaces: Array[Class[_]], constructor: ⇒ Actor, props: Props, address: String, loader: ClassLoader): R =
    createProxy[R](interfaces, (ref: AtomVar[R]) ⇒ constructor, props, address, loader)

  /* Internal API */

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

  private[akka] def createProxy[R <: AnyRef](interfaces: Array[Class[_]], constructor: (AtomVar[R]) ⇒ Actor, props: Props, address: String, loader: ClassLoader): R = {
    val proxyVar = new AtomVar[R]
    configureAndProxyLocalActorRef[R](interfaces, proxyVar, props.withCreator(constructor(proxyVar)), address, loader)
  }

  private[akka] def createProxyAndTypedActor[R <: AnyRef, T <: R](interface: Class[_], constructor: ⇒ T, props: Props, address: String, loader: ClassLoader): R =
    createProxy[R](extractInterfaces(interface), (ref: AtomVar[R]) ⇒ new TypedActor[R, T](ref, constructor), props, address, loader)

  private[akka] def configureAndProxyLocalActorRef[T <: AnyRef](interfaces: Array[Class[_]], proxyVar: AtomVar[T], props: Props, address: String, loader: ClassLoader): T = {
    //Warning, do not change order of the following statements, it's some elaborate chicken-n-egg handling
    val actorVar = new AtomVar[ActorRef](null)
    val proxy: T = Proxy.newProxyInstance(loader, interfaces, new TypedActorInvocationHandler(actorVar)(props.timeout)).asInstanceOf[T]
    proxyVar.set(proxy) // Chicken and egg situation we needed to solve, set the proxy so that we can set the self-reference inside each receive
    val ref = actorOf(props, address)
    actorVar.set(ref) //Make sure the InvocationHandler gets ahold of the actor reference, this is not a problem since the proxy hasn't escaped this method yet
    proxyVar.get
  }

  private[akka] def extractInterfaces(clazz: Class[_]): Array[Class[_]] = if (clazz.isInterface) Array[Class[_]](clazz) else clazz.getInterfaces

  private[akka] class TypedActor[R <: AnyRef, T <: R](val proxyVar: AtomVar[R], createInstance: ⇒ T) extends Actor {

    // FIXME TypedActor register/unregister on postStop/preStart
    // override def preStart = Actor.registry.registerTypedActor(self, proxyVar.get) //Make sure actor registry knows about this actor
    // override def postStop = Actor.registry.unregisterTypedActor(self, proxyVar.get)

    val me = createInstance
    def receive = {
      case m: MethodCall ⇒
        selfReference set proxyVar.get
        try {
          if (m.isOneWay) m(me)
          else if (m.returnsFuture_?) {
            channel match {
              case p: ActorPromise ⇒ p completeWith m(me).asInstanceOf[Future[Any]]
              case _               ⇒ throw new IllegalStateException("Future-returning TypedActor didn't use ?/ask so cannot reply")
            }
          } else reply(m(me))

        } finally { selfReference set null }
    }
  }

  private[akka] class TypedActorInvocationHandler(actorVar: AtomVar[ActorRef])(implicit timeout: Timeout) extends InvocationHandler {
    def actor = actorVar.get

    def invoke(proxy: AnyRef, method: Method, args: Array[AnyRef]): AnyRef = method.getName match {
      case "toString" ⇒ actor.toString
      case "equals"   ⇒ (args.length == 1 && (proxy eq args(0)) || actor == getActorRefFor(args(0))).asInstanceOf[AnyRef] //Force boxing of the boolean
      case "hashCode" ⇒ actor.hashCode.asInstanceOf[AnyRef]
      case _ ⇒
        MethodCall(method, args) match {
          case m if m.isOneWay        ⇒ actor ! m; null //Null return value
          case m if m.returnsFuture_? ⇒ actor ? m
          case m if m.returnsJOption_? || m.returnsOption_? ⇒
            val f = actor ? m
            try { f.await } catch { case _: FutureTimeoutException ⇒ }
            f.value match {
              case None | Some(Right(null))     ⇒ if (m.returnsJOption_?) JOption.none[Any] else None
              case Some(Right(joption: AnyRef)) ⇒ joption
              case Some(Left(ex))               ⇒ throw ex
            }
          case m ⇒
            (actor ? m).get.asInstanceOf[AnyRef]
        }
    }
  }
}
