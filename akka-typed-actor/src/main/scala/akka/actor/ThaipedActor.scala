package akka.actor

import java.lang.reflect.{Method, InvocationHandler, Proxy}
import akka.dispatch.Future
import akka.japi.{Creator, Option => JOption}
import akka.actor.Actor.{actorOf, futureToAnyOptionAsTypedOption}

trait T {

}

trait TImpl {

}

object ThaipedActor {

  class ThaipedActor[TI](createInstance: => TI) extends Actor {
    val currentInstance = createInstance
    def receive = {
      case (method: Method, args: Array[AnyRef]) => method.getReturnType match {
        case c if c.isAssignableFrom(Void.TYPE) => //Handle sendOneWay
          method.invoke(currentInstance, args)
        case c if c.isAssignableFrom(classOf[Future[_]]) => //Handle non-blocking sends
          val r = method.invoke(currentInstance, args).asInstanceOf[Future[Any]]
          self.senderFuture.get completeWith r
        case c if c.isAssignableFrom(classOf[JOption[_]]) => //Handle Java options returns
          self reply method.invoke(currentInstance, args)
        case c if c.isAssignableFrom(classOf[Option[_]]) => //Handle Scala options returns
          self reply method.invoke(currentInstance, args)
        case _ => //Handle blocking request-reply sends
          self reply method.invoke(currentInstance, args)
      }
    }
  }

  protected[akka] def createInvocationHandler(actor: ActorRef): InvocationHandler = new InvocationHandler {

    def invoke(proxy: AnyRef, method: Method, args: Array[AnyRef]): AnyRef = method.getName match {
      case "equals" | "hashCode" => method.invoke(proxy, args)
      case _ =>
        method.getReturnType match {
          case c if c.isAssignableFrom(Void.TYPE) => //Handle sendOneWay
            actor ! ((method, args))
            null
          case c if c.isAssignableFrom(classOf[Future[_]]) => //Handle non-blocking sends
            actor !!! ((method, args))
          case c if c.isAssignableFrom(classOf[JOption[_]]) => //Handle Java options returns
            (actor !!! ((method, args))).as[JOption[Any]] match {
              case Some(null) => JOption.none[Any]
              case Some(joption) => joption
              case None => JOption.none[Any]
            }
          case c if c.isAssignableFrom(classOf[Option[_]]) => //Handle Scala options returns
            (actor !!! ((method, args))).as[AnyRef] match {
              case Some(null) => None
              case Some(option) => option
              case None => None
            }
          case _ => //Handle blocking request-reply sends
            (actor !!! ((method, args))).get
        }
    }
  }

  def thaipedActorOf[T,TI <: T](interface: Class[T], impl: Class[TI], loader: ClassLoader): T =
    newThaipedActor(interface, impl.newInstance.asInstanceOf[TI],loader)

  def thaipedActorOf[T,TI <: T](interface: Class[T], impl: Creator[TI], loader: ClassLoader): T =
    newThaipedActor(interface, impl.create, loader)


  protected def newThaipedActor[T, TI <: T](interface: Class[T], impl: => TI, loader: ClassLoader = Thread.currentThread.getContextClassLoader): T =
    Proxy.newProxyInstance(loader, Array[Class[_]](interface), createInvocationHandler(actorOf(new ThaipedActor[TI](impl)).start)).asInstanceOf[T]
}