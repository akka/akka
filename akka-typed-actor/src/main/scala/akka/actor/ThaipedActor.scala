package akka.thaipedactor

import java.lang.reflect.{ Method, InvocationHandler, Proxy }
import akka.japi.{ Creator, Option ⇒ JOption }
import akka.actor.Actor.{ actorOf, futureToAnyOptionAsTypedOption }
import akka.transactor.annotation.{ Coordinated ⇒ CoordinatedAnnotation }
import akka.util.Duration
import akka.dispatch.{ MessageDispatcher, Dispatchers, AlreadyCompletedFuture, Future }
import akka.actor.{ ActorRef, Actor }

object MethodCall {
  private[akka] def isOneWay(method: Method): Boolean =
    method.getReturnType == java.lang.Void.TYPE

  private[akka] def isCoordinated(method: Method): Boolean =
    method.isAnnotationPresent(classOf[CoordinatedAnnotation])

  private[akka] def returnsFuture_?(method: Method): Boolean =
    classOf[Future[_]].isAssignableFrom(method.getReturnType)

  private[akka] def returnsJOption_?(method: Method): Boolean =
    classOf[akka.japi.Option[_]].isAssignableFrom(method.getReturnType)

  private[akka] def returnsOption_?(method: Method): Boolean =
    classOf[scala.Option[_]].isAssignableFrom(method.getReturnType)

  //Note to self: move resolveMethod from NettyRemoteSupport to here for remote typed actors
}

case class MethodCall(method: Method, parameters: Array[AnyRef]) {
  def isOneWay = MethodCall.isOneWay(method)
  def isCoordinated = MethodCall.isCoordinated(method)
  def returnsFuture_? = MethodCall.returnsFuture_?(method)
  def returnsJOption_? = MethodCall.returnsJOption_?(method)
  def returnsOption_? = MethodCall.returnsOption_?(method)

  def callMethodOn(instance: AnyRef): AnyRef = {

    /*if (parameters ne null)
      println("### CALLING " + method.getName + "(" + parameters.mkString(", ") + ") owned by [" + method.getDeclaringClass.getName + "] on " + instance + " of class " + instance.getClass.getName)
    else
      println("### CALLING " + method.getName + "() owned by [" + method.getDeclaringClass.getName + "] on " + instance + " of class " + instance.getClass.getName)*/

    //We do not yet obey Actor.SERIALIZE_MESSAGES
    parameters match {
      case null                     ⇒ method.invoke(instance)
      case args if args.length == 0 ⇒ method.invoke(instance)
      case args                     ⇒ method.invoke(instance, args: _*)
    }
  }
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
      case "equals"   ⇒ if (proxy eq args(0)) java.lang.Boolean.TRUE else java.lang.Boolean.FALSE
      case "hashCode" ⇒ actor.hashCode.asInstanceOf[AnyRef]
      case _ ⇒
        MethodCall(method, args) match {
          case m if m.isOneWay ⇒
            actor ! m
            null

          case m if m.returnsJOption_? ⇒
            (actor !!! m).as[JOption[Any]] match {
              case Some(null)    ⇒ JOption.none[Any]
              case Some(joption) ⇒ joption
              case None          ⇒ JOption.none[Any]
            }

          case m if m.returnsOption_? ⇒
            (actor !!! m).as[AnyRef] match {
              case Some(null)   ⇒ None
              case Some(option) ⇒ option
              case None         ⇒ None
            }

          case m if m.returnsFuture_? ⇒
            actor !!! m

          case m ⇒
            (actor !!! m).get
        }
    }
  }

  val defaultTimeout = Duration(Actor.TIMEOUT, "millis")

  case class Configuration(timeout: Duration = defaultTimeout, dispatcher: MessageDispatcher = Dispatchers.defaultGlobalDispatcher)

  def thaipedActorOf[T <: AnyRef, TI <: T](interface: Class[T], impl: Class[TI], config: Configuration, loader: ClassLoader): T =
    newThaipedActor(interface, impl.newInstance.asInstanceOf[TI], config, loader)

  def thaipedActorOf[T <: AnyRef, TI <: T](interface: Class[T], impl: Creator[TI], config: Configuration, loader: ClassLoader): T =
    newThaipedActor(interface, impl.create, config, loader)

  def thaipedActorOf[T <: AnyRef: Manifest, TI <: T](impl: Creator[TI], config: Configuration, loader: ClassLoader): T =
    newThaipedActor[T, TI](implicitly[Manifest[T]].erasure.asInstanceOf[Class[T]], impl.create, config, loader)

  protected def newThaipedActor[T <: AnyRef, TI <: T](interface: Class[T], impl: ⇒ TI, config: Configuration, loader: ClassLoader): T = {
    val actor = actorOf(new ThaipedActor[TI](impl))

    actor.timeout = config.timeout.toMillis
    actor.dispatcher = config.dispatcher

    val handler = new ThaipedActorInvocationHandler(actor)
    val proxy: T = Proxy.newProxyInstance(loader, Array[Class[_]](interface), handler).asInstanceOf[T]
    actor.start
    proxy
  }

  def stop(thaipedActor: AnyRef): Boolean = getActorFor(thaipedActor) match {
    case Some(ref) ⇒
      ref.stop
      true
    case _ ⇒ false
  }

  def getActorFor(thaipedActor: AnyRef): Option[ActorRef] = thaipedActor match {
    case null ⇒ None
    case other ⇒
      Proxy.getInvocationHandler(other) match {
        case null                                   ⇒ None
        case handler: ThaipedActorInvocationHandler ⇒ Option(handler.actor)
        case _                                      ⇒ None
      }
  }

  def isThaipedActor(thaipedActor_? : AnyRef): Boolean = thaipedActor_? match {
    case null ⇒ false
    case some ⇒ Proxy.getInvocationHandler(some) match {
      case null                                   ⇒ false
      case handler: ThaipedActorInvocationHandler ⇒ true
      case _                                      ⇒ false
    }
  }
}