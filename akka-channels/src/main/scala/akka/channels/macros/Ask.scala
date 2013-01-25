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

  def impl[ReturnT, Channel <: ChannelList: c.WeakTypeTag, Msg: c.WeakTypeTag](c: Context {
                                                                                 type PrefixType = ChannelRef[Channel]
                                                                               })(msg: c.Expr[Msg]): c.Expr[Future[ReturnT]] = {
    import c.universe._

    val tpeChannel = weakTypeOf[Channel]
    val tpeMsg = weakTypeOf[Msg]
    val out = replyChannels(c.universe)(tpeChannel, tpeMsg)
    if (out.isEmpty)
      abort(c, s"This ChannelRef does not support messages of type $tpeMsg")

    implicit val ttReturn = c.TypeTag[ReturnT](c.universe.lub(out))
    reify(askOps[ReturnT](c.prefix.splice.actorRef, msg.splice)(imp[Timeout](c).splice))
  }

  def opsImpl[ReturnT, Channel <: ChannelList: c.WeakTypeTag, Msg: c.WeakTypeTag](c: Context {
                                                                                    type PrefixType = AnyOps[Msg]
                                                                                  })(channel: c.Expr[ChannelRef[Channel]]): c.Expr[Future[ReturnT]] = {
    import c.universe._

    val tpeChannel = weakTypeOf[Channel]
    val tpeMsg = weakTypeOf[Msg]
    val out = replyChannels(c.universe)(tpeChannel, tpeMsg)
    if (out.isEmpty)
      abort(c, s"This ChannelRef does not support messages of type $tpeMsg")

    implicit val ttReturn = c.TypeTag[ReturnT](c.universe.lub(out))
    reify(askOps[ReturnT](channel.splice.actorRef, c.prefix.splice.value)(imp[Timeout](c).splice))
  }

  def futureImpl[ReturnT, Channel <: ChannelList: c.WeakTypeTag, Msg: c.WeakTypeTag](c: Context {
                                                                                       type PrefixType = FutureOps[Msg]
                                                                                     })(channel: c.Expr[ChannelRef[Channel]]): c.Expr[Future[ReturnT]] = {
    import c.universe._

    val tpeChannel = weakTypeOf[Channel]
    val tpeMsg = weakTypeOf[Msg]
    val reply = replyChannels(c.universe)(tpeChannel, tpeMsg) match {
      case Nil      ⇒ abort(c, s"This ChannelRef does not support messages of type $tpeMsg")
      case x :: Nil ⇒ x
      case xs       ⇒ toChannels(c.universe)(xs)
    }

    implicit val ttReturn = c.TypeTag[ReturnT](reply)
    reify(askFuture[Msg, ReturnT](channel.splice.actorRef, c.prefix.splice.future)(imp[Timeout](c).splice))
  }

  @inline def askOps[T](target: ActorRef, msg: Any)(implicit t: Timeout): Future[T] = akka.pattern.ask(target, msg).asInstanceOf[Future[T]]

  def askFuture[T1, T2](target: ActorRef, future: Future[T1])(implicit t: Timeout): Future[T2] =
    future.flatMap(akka.pattern.ask(target, _).asInstanceOf[Future[T2]])(ExecutionContexts.sameThreadExecutionContext)

}