package akka.actor

/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

import akka.japi.{ Creator, Option ⇒ JOption }
import akka.actor.Actor.{ actorOf, futureToAnyOptionAsTypedOption }
import akka.dispatch.{ MessageDispatcher, Dispatchers, Future }
import java.lang.reflect.{ InvocationTargetException, Method, InvocationHandler, Proxy }
import akka.util.{ Duration }
import java.util.concurrent.atomic.AtomicReference

object TypedActor {
  private val selfReference = new ThreadLocal[AnyRef]

  def self[T <: AnyRef] = selfReference.get.asInstanceOf[T] match {
    case null ⇒ throw new IllegalStateException("Calling TypedActor.self outside of a TypedActor implementation method!")
    case some ⇒ some
  }

  class TypedActor[R <: AnyRef, T <: R](val proxyRef: AtomicReference[R], createInstance: ⇒ T) extends Actor {
    val me = createInstance
    def callMethod(methodCall: MethodCall): Unit = methodCall match {
      case m if m.isOneWay        ⇒ m(me)
      case m if m.returnsFuture_? ⇒ self.senderFuture.get completeWith m(me).asInstanceOf[Future[Any]]
      case m                      ⇒ self reply m(me)
    }
    def receive = {
      case m: MethodCall ⇒
        selfReference set proxyRef.get
        try { callMethod(m) } finally { selfReference set null }
    }
  }

  case class TypedActorInvocationHandler(actor: ActorRef) extends InvocationHandler {
    def invoke(proxy: AnyRef, method: Method, args: Array[AnyRef]): AnyRef = method.getName match {
      case "toString" ⇒ actor.toString
      case "equals"   ⇒ ((proxy eq args(0)) || actor == getActorRefFor(args(0))).asInstanceOf[AnyRef] //Force boxing of the boolean
      case "hashCode" ⇒ actor.hashCode.asInstanceOf[AnyRef]
      case _ ⇒
        MethodCall(method, args) match {
          case m if m.isOneWay ⇒
            actor ! m
            null
          case m if m.returnsFuture_? ⇒
            actor !!! m
          case m if m.returnsJOption_? || m.returnsOption_? ⇒
            (actor !!! m).as[AnyRef] match {
              case Some(null) | None ⇒ if (m.returnsJOption_?) JOption.none[Any] else None
              case Some(joption)     ⇒ joption
            }
          case m ⇒
            (actor !!! m).get
        }
    }
  }

  case class Configuration(timeout: Duration = Duration(Actor.TIMEOUT, "millis"), dispatcher: MessageDispatcher = Dispatchers.defaultGlobalDispatcher)

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

  def getActorRefFor(typedActor: AnyRef): ActorRef = typedActor match {
    case null ⇒ null
    case other ⇒ Proxy.getInvocationHandler(other) match {
      case null                                 ⇒ null
      case handler: TypedActorInvocationHandler ⇒ handler.actor
      case _                                    ⇒ null
    }
  }

  def isTypedActor(typedActor_? : AnyRef): Boolean = getActorRefFor(typedActor_?) ne null

  private[akka] def createProxyAndTypedActor[R <: AnyRef, T <: R](interface: Class[_], constructor: ⇒ T, config: Configuration, loader: ClassLoader): R =
    createProxy[R](extractInterfaces(interface), (ref: AtomicReference[R]) ⇒ new TypedActor[R, T](ref, constructor), config, loader)

  def createProxy[R <: AnyRef](constructor: ⇒ Actor, config: Configuration = Configuration(), loader: ClassLoader = null)(implicit m: Manifest[R]): R =
    createProxy[R](extractInterfaces(m.erasure), (ref: AtomicReference[R]) ⇒ constructor, config, if (loader eq null) m.erasure.getClassLoader else loader)

  def createProxy[R <: AnyRef](interfaces: Array[Class[_]], constructor: ⇒ Actor, config: Configuration, loader: ClassLoader): R =
    createProxy[R](interfaces, (ref: AtomicReference[R]) ⇒ constructor, config, loader)

  def createProxy[R <: AnyRef](interfaces: Array[Class[_]], constructor: (AtomicReference[R]) ⇒ Actor, config: Configuration, loader: ClassLoader): R = {
    val proxyRef = new AtomicReference[R]
    configureAndProxyLocalActorRef[R](interfaces, proxyRef, actorOf(constructor(proxyRef)), config, loader)
  }

  protected def configureAndProxyLocalActorRef[T <: AnyRef](interfaces: Array[Class[_]], proxyRef: AtomicReference[T], actor: ActorRef, config: Configuration, loader: ClassLoader): T = {
    actor.timeout = config.timeout.toMillis
    actor.dispatcher = config.dispatcher

    val proxy: T = Proxy.newProxyInstance(loader, interfaces, new TypedActorInvocationHandler(actor)).asInstanceOf[T]
    proxyRef.set(proxy) // Chicken and egg situation we needed to solve, set the proxy so that we can set the self-reference inside each receive
    Actor.registry.registerTypedActor(actor.start, proxy) //We only have access to the proxy from the outside, so register it with the ActorRegistry, will be removed on actor.stop
    proxy
  }

  private[akka] def extractInterfaces(clazz: Class[_]): Array[Class[_]] =
    if (clazz.isInterface) Array[Class[_]](clazz)
    else clazz.getInterfaces
}