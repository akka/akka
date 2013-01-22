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

object Ask {
  import Helpers._

  def impl[T <: ChannelList: c.WeakTypeTag, M: c.WeakTypeTag](c: Context {
                                                                type PrefixType = ChannelRef[T]
                                                              })(msg: c.Expr[M]): c.Expr[Future[_]] = {
    import c.universe._
    askTree(c)(weakTypeOf[M], weakTypeOf[T])(reify(c.prefix.splice.actorRef), msg)
  }

  def opsImpl[T <: ChannelList: c.WeakTypeTag, M: c.WeakTypeTag](c: Context {
                                                                   type PrefixType = AnyOps[M]
                                                                 })(channel: c.Expr[ChannelRef[T]]): c.Expr[Future[_]] = {
    import c.universe._
    askTree(c)(weakTypeOf[M], weakTypeOf[T])(reify(channel.splice.actorRef), reify(c.prefix.splice.value))
  }

  def futureImpl[T <: ChannelList: c.WeakTypeTag, M: c.WeakTypeTag](c: Context {
                                                                      type PrefixType = FutureOps[M]
                                                                    })(channel: c.Expr[ChannelRef[T]]): c.Expr[Future[_]] = {
    import c.universe._
    val tree = askTree(c)(weakTypeOf[M], weakTypeOf[T])(c.Expr(Ident("c$1")), c.Expr(Ident("x$1")))
    reify({
      val c$1 = channel.splice.actorRef
      c.prefix.splice.future.flatMap(x$1 ⇒ tree.splice)(ExecutionContexts.sameThreadExecutionContext)
    })
  }

  def askTree[M](c: Context with Singleton)(msgT: c.universe.Type, chT: c.universe.Type)(target: c.Expr[ActorRef], msg: c.Expr[M]): c.Expr[Future[_]] = {
    import c.universe._
    val out = replyChannels(c.universe)(chT, msgT)
    if (out.isEmpty) {
      c.error(c.enclosingPosition, s"This ChannelRef does not support messages of type $msgT")
      reify(null)
    } else {
      val timeout = c.inferImplicitValue(typeOf[Timeout])
      if (timeout.isEmpty)
        c.error(c.enclosingPosition, s"no implicit akka.util.Timeout found")
      val result = appliedType(weakTypeOf[Future[_]].typeConstructor, List(lub(out)))
      c.Expr(
        TypeApply(
          Select(
            reify(akka.pattern.ask(
              target.splice, msg.splice)(
                c.Expr(timeout)(weakTypeTag[Timeout]).splice)).tree,
            "asInstanceOf"),
          List(TypeTree().setType(result))))(weakTypeTag[Future[_]])
    }
  }

}