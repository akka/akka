package akka.actor

import java.lang.reflect.{Method, InvocationHandler, Proxy}
import akka.japi.{Creator, Option => JOption}
import akka.actor.Actor.{actorOf, futureToAnyOptionAsTypedOption}
import akka.dispatch.{AlreadyCompletedFuture, Future}

trait Foo {
  def pigdog(): String

  def futurePigdog(): Future[String]
  def futurePigdog(delay: Long): Future[String]

  def optionPigdog(): Option[String]
}

class Bar extends Foo {
  def pigdog = "Bar"

  def futurePigdog(): Future[String] = new AlreadyCompletedFuture(Right(pigdog))
  def futurePigdog(delay: Long): Future[String] = {
    Thread.sleep(delay)
    futurePigdog
  }

  def optionPigdog(): Option[String] = Some(pigdog)

}

object ThaipedActor {

  class ThaipedActor[TI](createInstance: => TI) extends Actor {
    val currentInstance = createInstance
    def receive = {
      case Invocation(method, args) =>

        def invokeMethod(): AnyRef = args match {
          case null => method.invoke(currentInstance)
          case some => method.invoke(currentInstance, some:_*)
        }


      method.getReturnType match {
        case c if c.isAssignableFrom(Void.TYPE) => //Handle sendOneWay
          invokeMethod()
        case c if c.isAssignableFrom(classOf[Future[_]]) => //Handle non-blocking sends
          val r = invokeMethod().asInstanceOf[Future[Any]]
          self.senderFuture.get completeWith r
        case c if c.isAssignableFrom(classOf[JOption[_]]) => //Handle Java options returns
          self reply invokeMethod()
        case c if c.isAssignableFrom(classOf[Option[_]]) => //Handle Scala options returns
          self reply invokeMethod()
        case _ => //Handle blocking request-reply sends
          self reply invokeMethod()
      }
    }
  }

  case class Invocation(method: Method, args: Array[AnyRef])

  protected[akka] def createInvocationHandler(actor: ActorRef): InvocationHandler = new InvocationHandler {

    def invoke(proxy: AnyRef, method: Method, args: Array[AnyRef]): AnyRef = method.getName match {
      case "toString" => actor.toString
      case "equals" => if (proxy eq args(0)) java.lang.Boolean.TRUE else java.lang.Boolean.FALSE
      case _ =>
        method.getReturnType match {
          case c if c.isAssignableFrom(Void.TYPE) => //Handle sendOneWay
            actor ! Invocation(method, args)
            null
          case c if c.isAssignableFrom(classOf[Future[_]]) => //Handle non-blocking sends
            actor !!! Invocation(method, args)
          case c if c.isAssignableFrom(classOf[JOption[_]]) => //Handle Java options returns
            (actor !!! Invocation(method, args)).as[JOption[Any]] match {
              case Some(null) => JOption.none[Any]
              case Some(joption) => joption
              case None => JOption.none[Any]
            }
          case c if c.isAssignableFrom(classOf[Option[_]]) => //Handle Scala options returns
            (actor !!! Invocation(method, args)).as[AnyRef] match {
              case Some(null) => None
              case Some(option) => option
              case None => None
            }
          case _ => //Handle blocking request-reply sends
            (actor !!! Invocation(method, args)).get
        }
    }
  }

  def thaipedActorOf[T,TI <: T](interface: Class[T], impl: Class[TI], loader: ClassLoader): T =
    newThaipedActor(interface, impl.newInstance.asInstanceOf[TI],loader)

  def thaipedActorOf[T,TI <: T](interface: Class[T], impl: Creator[TI], loader: ClassLoader): T =
    newThaipedActor(interface, impl.create, loader)

  def thaipedActorOf[T : Manifest, TI <: T](impl: Creator[TI], loader: ClassLoader): T =
    newThaipedActor[T,TI](implicitly[Manifest[T]].erasure.asInstanceOf[Class[T]], impl, loader)


  protected def newThaipedActor[T, TI <: T](interface: Class[T], impl: => TI, loader: ClassLoader): T =
    Proxy.newProxyInstance(loader, Array[Class[_]](interface), createInvocationHandler(actorOf(new ThaipedActor[TI](impl)).start)).asInstanceOf[T]
}