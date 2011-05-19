package akka.actor

/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

import akka.japi.{ Creator, Option ⇒ JOption }
import akka.actor.Actor.{ actorOf, futureToAnyOptionAsTypedOption }
import akka.dispatch.{ MessageDispatcher, Dispatchers, Future }
import java.lang.reflect.{ InvocationTargetException, Method, InvocationHandler, Proxy }
import akka.util.{ Duration }

object TypedActor {
  class TypedActor[TI <: AnyRef](createInstance: ⇒ TI) extends Actor {
    val me = createInstance
    def receive = {
      case m: MethodCall ⇒ m match {
        case m if m.isOneWay        ⇒ m(me)
        case m if m.returnsFuture_? ⇒ self.senderFuture.get completeWith m(me).asInstanceOf[Future[Any]]
        case m                      ⇒ self reply m(me)
      }
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
          case m if m.returnsJOption_? ⇒
            (actor !!! m).as[JOption[Any]] match {
              case Some(null) | None ⇒ JOption.none[Any]
              case Some(joption)     ⇒ joption
            }
          case m if m.returnsOption_? ⇒
            (actor !!! m).as[AnyRef] match {
              case Some(null) | None ⇒ None
              case Some(option)      ⇒ option
            }
          case m if m.returnsFuture_? ⇒
            actor !!! m
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

  def typedActorOf[T <: AnyRef, TI <: T](interface: Class[T], impl: Class[TI], config: Configuration): T =
    configureAndProxy(Array[Class[_]](interface), actorOf(new TypedActor[TI](impl.newInstance.asInstanceOf[TI])), config, interface.getClassLoader)

  def typedActorOf[T <: AnyRef, TI <: T](interface: Class[T], impl: Creator[TI], config: Configuration): T =
    configureAndProxy(Array[Class[_]](interface), actorOf(new TypedActor[TI](impl.create)), config, interface.getClassLoader)

  def typedActorOf[T <: AnyRef, TI <: T](interface: Class[T], impl: Class[TI], config: Configuration, loader: ClassLoader): T =
    configureAndProxy(Array[Class[_]](interface), actorOf(new TypedActor[TI](impl.newInstance.asInstanceOf[TI])), config, loader)

  def typedActorOf[T <: AnyRef, TI <: T](interface: Class[T], impl: Creator[TI], config: Configuration, loader: ClassLoader): T =
    configureAndProxy(Array[Class[_]](interface), actorOf(new TypedActor[TI](impl.create)), config, loader)

  def typedActorOf[R <: AnyRef, T <: R](impl: Class[T], config: Configuration, loader: ClassLoader): R =
    configureAndProxy(impl.getInterfaces, actorOf(new TypedActor[T](impl.newInstance)), config, loader)

  def typedActorOf[R <: AnyRef, T <: R](config: Configuration = Configuration(), loader: ClassLoader = null)(implicit m: Manifest[T]): R = {
    val clazz = m.erasure.asInstanceOf[Class[T]]
    configureAndProxy[R](clazz.getInterfaces, actorOf(new TypedActor[T](clazz.newInstance)), config, if (loader eq null) clazz.getClassLoader else loader)
  }

  protected def configureAndProxy[T <: AnyRef](interfaces: Array[Class[_]], actor: ActorRef, config: Configuration, loader: ClassLoader): T = {
    actor.timeout = config.timeout.toMillis
    actor.dispatcher = config.dispatcher

    val proxy: T = Proxy.newProxyInstance(loader, interfaces, new TypedActorInvocationHandler(actor)).asInstanceOf[T]
    Actor.registry.registerTypedActor(actor.start, proxy) //We only have access to the proxy from the outside, so register it with the ActorRegistry, will be removed on actor.stop
    proxy
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
}