package akka.channels.macros

import akka.channels._
import scala.concurrent.Future
import akka.util.Timeout
import scala.reflect.runtime.{ universe ⇒ ru }
import ru.TypeTag
import scala.reflect.macros.Context
import scala.reflect.api.Universe
import akka.actor.ActorRef
import akka.dispatch.ExecutionContexts
import scala.reflect.api.{ TypeCreator }

object Ask {
  import Helpers._

  def impl[A, T <: ChannelList: c.WeakTypeTag, M: c.WeakTypeTag](c: Context {
                                                                   type PrefixType = ChannelRef[T]
                                                                 })(msg: c.Expr[M]): c.Expr[Future[A]] = {
    import c.universe._

    val chT = weakTypeOf[T]
    val msgT = weakTypeOf[M]
    val out = replyChannels(c.universe)(chT, msgT)
    if (out.isEmpty)
      abort(c, s"This ChannelRef does not support messages of type $msgT")

    implicit val tA = weakTT[A](c)(c.universe.lub(out))
    reify(askOps[A](c.prefix.splice.actorRef, msg.splice)(imp[Timeout](c).splice))
  }

  def opsImpl[A, T <: ChannelList: c.WeakTypeTag, M: c.WeakTypeTag](c: Context {
                                                                      type PrefixType = AnyOps[M]
                                                                    })(channel: c.Expr[ChannelRef[T]]): c.Expr[Future[A]] = {
    import c.universe._

    val chT = weakTypeOf[T]
    val msgT = weakTypeOf[M]
    val out = replyChannels(c.universe)(chT, msgT)
    if (out.isEmpty)
      abort(c, s"This ChannelRef does not support messages of type $msgT")

    implicit val tA = weakTT[A](c)(c.universe.lub(out))
    reify(askOps[A](channel.splice.actorRef, c.prefix.splice.value)(imp[Timeout](c).splice))
  }

  def futureImpl[A, T <: ChannelList: c.WeakTypeTag, M: c.WeakTypeTag](c: Context {
                                                                         type PrefixType = FutureOps[M]
                                                                       })(channel: c.Expr[ChannelRef[T]]): c.Expr[Future[A]] = {
    import c.universe._

    val chT = weakTypeOf[T]
    val msgT = weakTypeOf[M]
    val reply = replyChannels(c.universe)(chT, msgT) match {
      case Nil      ⇒ abort(c, s"This ChannelRef does not support messages of type $msgT")
      case x :: Nil ⇒ x
      case xs       ⇒ toChannels(c.universe)(xs)
    }

    implicit val tA = weakTT[A](c)(reply)
    reify(askFuture[M, A](channel.splice.actorRef, c.prefix.splice.future)(imp[Timeout](c).splice))
  }

  @inline def askOps[T](target: ActorRef, msg: Any)(implicit t: Timeout): Future[T] = akka.pattern.ask(target, msg).asInstanceOf[Future[T]]

  def askFuture[T1, T2](target: ActorRef, future: Future[T1])(implicit t: Timeout): Future[T2] =
    future.flatMap(akka.pattern.ask(target, _).asInstanceOf[Future[T2]])(ExecutionContexts.sameThreadExecutionContext)

}