package akka.actor

/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

import akka.japi.{ Creator, Option ⇒ JOption }
import akka.actor.Actor._
import akka.dispatch.{ MessageDispatcher, Dispatchers, Future, FutureTimeoutException }
import java.lang.reflect.{ InvocationTargetException, Method, InvocationHandler, Proxy }
import akka.util.{ Duration }
import java.util.concurrent.atomic.{ AtomicReference ⇒ AtomVar }

//TODO Document this class, not only in Scaladoc, but also in a dedicated typed-actor.rst, for both java and scala
object TypedActor {
  private val selfReference = new ThreadLocal[AnyRef]

  def self[T <: AnyRef] = selfReference.get.asInstanceOf[T] match {
    case null ⇒ throw new IllegalStateException("Calling TypedActor.self outside of a TypedActor implementation method!")
    case some ⇒ some
  }

  private[akka] class TypedActor[R <: AnyRef, T <: R](val proxyRef: AtomVar[R], createInstance: ⇒ T) extends Actor {
    val me = createInstance
    def receive = {
      case m: MethodCall ⇒
        selfReference set proxyRef.get
        try {
          m match {
            case m if m.isOneWay        ⇒ m(me)
            case m if m.returnsFuture_? ⇒ self.senderFuture.get completeWith m(me).asInstanceOf[Future[Any]]
            case m                      ⇒ self reply m(me)
          }
        } finally { selfReference set null }
    }
  }

  case class TypedActorInvocationHandler(actor: ActorRef) extends InvocationHandler {
    def invoke(proxy: AnyRef, method: Method, args: Array[AnyRef]): AnyRef = method.getName match {
      case "toString" ⇒ actor.toString
      case "equals"   ⇒ (args.length == 1 && (proxy eq args(0)) || actor == getActorRefFor(args(0))).asInstanceOf[AnyRef] //Force boxing of the boolean
      case "hashCode" ⇒ actor.hashCode.asInstanceOf[AnyRef]
      case _ ⇒
        implicit val timeout = Actor.Timeout(actor.timeout)
        MethodCall(method, args) match {
          case m if m.isOneWay ⇒
            actor ! m
            null
          case m if m.returnsFuture_? ⇒
            actor ? m
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

  object Configuration { //TODO: Replace this with the new ActorConfiguration when it exists
    val defaultTimeout = Duration(Actor.TIMEOUT, "millis")
    val defaultConfiguration = new Configuration(defaultTimeout, Dispatchers.defaultGlobalDispatcher)
    def apply(): Configuration = defaultConfiguration
  }
  case class Configuration(timeout: Duration = Configuration.defaultTimeout, dispatcher: MessageDispatcher = Dispatchers.defaultGlobalDispatcher)

  case class MethodCall(method: Method, parameters: Array[AnyRef]) {
    def isOneWay = method.getReturnType == java.lang.Void.TYPE
    def returnsFuture_? = classOf[Future[_]].isAssignableFrom(method.getReturnType)
    def returnsJOption_? = classOf[akka.japi.Option[_]].isAssignableFrom(method.getReturnType)
    def returnsOption_? = classOf[scala.Option[_]].isAssignableFrom(method.getReturnType)

    def apply(instance: AnyRef): AnyRef = try {
      parameters match { //We do not yet obey Actor.SERIALIZE_MESSAGES
        case null                     ⇒ method.invoke(instance)
        case args if args.length == 0 ⇒ method.invoke(instance)
        case args                     ⇒ method.invoke(instance, args: _*)
      }
    } catch { case i: InvocationTargetException ⇒ throw i.getTargetException }

    private def writeReplace(): AnyRef = new SerializedMethodCall(method.getDeclaringClass, method.getName, method.getParameterTypes, parameters)
  }

  case class SerializedMethodCall(ownerType: Class[_], methodName: String, parameterTypes: Array[Class[_]], parameterValues: Array[AnyRef]) {
    //TODO implement writeObject and readObject to serialize
    //TODO Possible optimization is to special encode the parameter-types to conserve space
    private def readResolve(): AnyRef = MethodCall(ownerType.getDeclaredMethod(methodName, parameterTypes: _*), parameterValues)
  }

  def typedActorOf[R <: AnyRef, T <: R](interface: Class[R], impl: Class[T], config: Configuration): R =
    createProxyAndTypedActor(interface, impl.newInstance, config, interface.getClassLoader)

  def typedActorOf[R <: AnyRef, T <: R](interface: Class[R], impl: Creator[T], config: Configuration): R =
    createProxyAndTypedActor(interface, impl.create, config, interface.getClassLoader)

  def typedActorOf[R <: AnyRef, T <: R](interface: Class[R], impl: Class[T], config: Configuration, loader: ClassLoader): R =
    createProxyAndTypedActor(interface, impl.newInstance, config, loader)

  def typedActorOf[R <: AnyRef, T <: R](interface: Class[R], impl: Creator[T], config: Configuration, loader: ClassLoader): R =
    createProxyAndTypedActor(interface, impl.create, config, loader)

  def typedActorOf[R <: AnyRef, T <: R](impl: Class[T], config: Configuration, loader: ClassLoader): R =
    createProxyAndTypedActor(impl, impl.newInstance, config, loader)

  def typedActorOf[R <: AnyRef, T <: R](config: Configuration = Configuration(), loader: ClassLoader = null)(implicit m: Manifest[T]): R = {
    val clazz = m.erasure.asInstanceOf[Class[T]]
    createProxyAndTypedActor(clazz, clazz.newInstance, config, if (loader eq null) clazz.getClassLoader else loader)
  }

  def stop(typedActor: AnyRef): Boolean = getActorRefFor(typedActor) match {
    case null ⇒ false
    case ref  ⇒ ref.stop; true
  }

  def getActorRefFor(typedActor: AnyRef): ActorRef = invocationHandlerFor(typedActor) match {
    case null    ⇒ null
    case handler ⇒ handler.actor
  }

  def invocationHandlerFor(typedActor_? : AnyRef): TypedActorInvocationHandler =
    if ((typedActor_? ne null) && Proxy.isProxyClass(typedActor_?.getClass)) typedActor_? match {
      case null ⇒ null
      case other ⇒ Proxy.getInvocationHandler(other) match {
        case null                                 ⇒ null
        case handler: TypedActorInvocationHandler ⇒ handler
        case _                                    ⇒ null
      }
    }
    else null

  def isTypedActor(typedActor_? : AnyRef): Boolean = invocationHandlerFor(typedActor_?) ne null

  def createProxy[R <: AnyRef](constructor: ⇒ Actor, config: Configuration = Configuration(), loader: ClassLoader = null)(implicit m: Manifest[R]): R =
    createProxy[R](extractInterfaces(m.erasure), (ref: AtomVar[R]) ⇒ constructor, config, if (loader eq null) m.erasure.getClassLoader else loader)

  def createProxy[R <: AnyRef](interfaces: Array[Class[_]], constructor: Creator[Actor], config: Configuration, loader: ClassLoader): R =
    createProxy(interfaces, (ref: AtomVar[R]) ⇒ constructor.create, config, loader)

  def createProxy[R <: AnyRef](interfaces: Array[Class[_]], constructor: ⇒ Actor, config: Configuration, loader: ClassLoader): R =
    createProxy[R](interfaces, (ref: AtomVar[R]) ⇒ constructor, config, loader)

  /* Internal API */

  private[akka] def createProxy[R <: AnyRef](interfaces: Array[Class[_]], constructor: (AtomVar[R]) ⇒ Actor, config: Configuration, loader: ClassLoader): R = {
    val proxyRef = new AtomVar[R]
    configureAndProxyLocalActorRef[R](interfaces, proxyRef, constructor(proxyRef), config, loader)
  }

  private[akka] def createProxyAndTypedActor[R <: AnyRef, T <: R](interface: Class[_], constructor: ⇒ T, config: Configuration, loader: ClassLoader): R =
    createProxy[R](extractInterfaces(interface), (ref: AtomVar[R]) ⇒ new TypedActor[R, T](ref, constructor), config, loader)

  private[akka] def configureAndProxyLocalActorRef[T <: AnyRef](interfaces: Array[Class[_]], proxyRef: AtomVar[T], actor: ⇒ Actor, config: Configuration, loader: ClassLoader): T = {

    val ref = actorOf(actor)

    ref.timeout = config.timeout.toMillis
    ref.dispatcher = config.dispatcher

    val proxy: T = Proxy.newProxyInstance(loader, interfaces, new TypedActorInvocationHandler(ref)).asInstanceOf[T]
    proxyRef.set(proxy) // Chicken and egg situation we needed to solve, set the proxy so that we can set the self-reference inside each receive
    Actor.registry.registerTypedActor(ref, proxy) //We only have access to the proxy from the outside, so register it with the ActorRegistry, will be removed on actor.stop
    proxy
  }

  private[akka] def extractInterfaces(clazz: Class[_]): Array[Class[_]] = if (clazz.isInterface) Array[Class[_]](clazz) else clazz.getInterfaces
}
