package akka.actor

/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

import akka.japi.{ Creator, Option ⇒ JOption }
import akka.actor.Actor.{ actorOf, futureToAnyOptionAsTypedOption }
import akka.dispatch.{ MessageDispatcher, Dispatchers, AlreadyCompletedFuture, Future }
import java.lang.reflect.{ InvocationTargetException, Method, InvocationHandler, Proxy }
import akka.util.{ Duration }
import akka.actor.{ ActorRef, Actor }

object MethodCall {
  def isOneWay(method: Method): Boolean = method.getReturnType == java.lang.Void.TYPE
  def returnsFuture_?(method: Method): Boolean = classOf[Future[_]].isAssignableFrom(method.getReturnType)
  def returnsJOption_?(method: Method): Boolean = classOf[akka.japi.Option[_]].isAssignableFrom(method.getReturnType)
  def returnsOption_?(method: Method): Boolean = classOf[scala.Option[_]].isAssignableFrom(method.getReturnType)
}

case class MethodCall(method: Method, parameters: Array[AnyRef]) {
  def isOneWay = MethodCall.isOneWay(method)
  def returnsFuture_? = MethodCall.returnsFuture_?(method)
  def returnsJOption_? = MethodCall.returnsJOption_?(method)
  def returnsOption_? = MethodCall.returnsOption_?(method)

  def callMethodOn(instance: AnyRef): AnyRef = try {
    parameters match { //We do not yet obey Actor.SERIALIZE_MESSAGES
      case null                     ⇒ method.invoke(instance)
      case args if args.length == 0 ⇒ method.invoke(instance)
      case args                     ⇒ method.invoke(instance, args: _*)
    }
  } catch {
    case i: InvocationTargetException ⇒ throw i.getTargetException
  }

  private def writeReplace(): AnyRef = new SerializedMethodCall(method.getDeclaringClass, method.getName, method.getParameterTypes, parameters)
}

case class SerializedMethodCall(ownerType: Class[_], methodName: String, parameterTypes: Array[Class[_]], parameterValues: Array[AnyRef]) {
  private def readResolve(): AnyRef = MethodCall(ownerType.getDeclaredMethod(methodName, parameterTypes: _*), parameterValues)
}

object ThaipedActor {

  class ThaipedActor[TI <: AnyRef](createInstance: ⇒ TI) extends Actor {
    val me = createInstance
    def receive = {
      case m: MethodCall ⇒ m match {
        case m if m.isOneWay        ⇒ m.callMethodOn(me)
        case m if m.returnsFuture_? ⇒ self.senderFuture.get completeWith m.callMethodOn(me).asInstanceOf[Future[Any]]
        case m                      ⇒ self reply m.callMethodOn(me)
      }
    }
  }

  case class ThaipedActorInvocationHandler(actor: ActorRef) extends InvocationHandler {
    def invoke(proxy: AnyRef, method: Method, args: Array[AnyRef]): AnyRef = method.getName match {
      case "toString" ⇒ actor.toString
      case "equals" ⇒
        if ((proxy eq args(0))) java.lang.Boolean.TRUE
        else getActorFor(args(0)) match {
          case Some(`actor`) ⇒ java.lang.Boolean.TRUE
          case _             ⇒ java.lang.Boolean.FALSE
        }
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

  def thaipedActorOf[T <: AnyRef, TI <: T](interface: Class[T], impl: Class[TI], config: Configuration): T =
    configureAndProxy(interface, actorOf(new ThaipedActor[TI](impl.newInstance.asInstanceOf[TI])), config, interface.getClassLoader)

  def thaipedActorOf[T <: AnyRef, TI <: T](interface: Class[T], impl: Creator[TI], config: Configuration): T =
    configureAndProxy(interface, actorOf(new ThaipedActor[TI](impl.create)), config, interface.getClassLoader)

  def thaipedActorOf[T <: AnyRef, TI <: T](interface: Class[T], impl: Class[TI], config: Configuration, loader: ClassLoader): T =
    configureAndProxy(interface, actorOf(new ThaipedActor[TI](impl.newInstance.asInstanceOf[TI])), config, loader)

  def thaipedActorOf[T <: AnyRef, TI <: T](interface: Class[T], impl: Creator[TI], config: Configuration, loader: ClassLoader): T =
    configureAndProxy(interface, actorOf(new ThaipedActor[TI](impl.create)), config, loader)

  protected def configureAndProxy[T <: AnyRef](interface: Class[T], actor: ActorRef, config: Configuration, loader: ClassLoader): T = {
    actor.timeout = config.timeout.toMillis
    actor.dispatcher = config.dispatcher

    val proxy: T = Proxy.newProxyInstance(loader, Array[Class[_]](interface), new ThaipedActorInvocationHandler(actor)).asInstanceOf[T]
    actor.start
    proxy
  }

  def stop(thaipedActor: AnyRef): Boolean = getActorFor(thaipedActor) match {
    case Some(ref) ⇒ ref.stop; true
    case _         ⇒ false
  }

  def getActorFor(thaipedActor: AnyRef): Option[ActorRef] = thaipedActor match {
    case null ⇒ None
    case other ⇒ Proxy.getInvocationHandler(other) match {
      case null                                   ⇒ None
      case handler: ThaipedActorInvocationHandler ⇒ Option(handler.actor)
      case _                                      ⇒ None
    }
  }

  def isThaipedActor(thaipedActor_? : AnyRef): Boolean = getActorFor(thaipedActor_?).isDefined
}