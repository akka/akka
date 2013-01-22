package akka.channels.macros

import akka.channels._
import akka.actor._
import scala.reflect.runtime.{ universe ⇒ ru }
import ru.TypeTag
import scala.reflect.macros.Context
import scala.reflect.api.Universe
import scala.annotation.tailrec
import scala.concurrent.Future
import scala.util.{ Failure, Success }
import akka.dispatch.ExecutionContexts

object Tell {
  import Helpers._

  def impl[T <: ChannelList: c.WeakTypeTag, M: c.WeakTypeTag](c: Context {
                                                                type PrefixType = ChannelRef[T]
                                                              })(msg: c.Expr[M]): c.Expr[Unit] = {
    val tT = c.universe.weakTypeOf[T]
    val (tS, senderTree, sender) = getSenderChannel(c)

    verify(c)(senderTree, c.universe.weakTypeOf[M], tS, tT)

    c.universe.reify(c.prefix.splice.actorRef.tell(msg.splice, sender.splice))
  }

  def opsImpl[T <: ChannelList: c.WeakTypeTag, M: c.WeakTypeTag](c: Context {
                                                                   type PrefixType = AnyOps[M]
                                                                 })(channel: c.Expr[ChannelRef[T]]): c.Expr[Unit] = {
    val tT = c.universe.weakTypeOf[T]
    val (tS, senderTree, sender) = getSenderChannel(c)

    verify(c)(senderTree, c.universe.weakTypeOf[M], tS, tT)

    c.universe.reify(channel.splice.actorRef.tell(c.prefix.splice.value, sender.splice))
  }

  def futureImpl[T <: ChannelList: c.WeakTypeTag, M: c.WeakTypeTag](c: Context {
                                                                      type PrefixType = FutureOps[M]
                                                                    })(channel: c.Expr[ChannelRef[T]]): c.Expr[Future[M]] = {
    val tT = c.universe.weakTypeOf[T]
    val (tS, senderTree, sender) = getSenderChannel(c)

    verify(c)(senderTree, c.universe.weakTypeOf[M], tS, tT)

    c.universe.reify(
      {
        val s$1 = sender.splice
        c.prefix.splice.future.andThen {
          case Success(s) ⇒ channel.splice.actorRef.tell(s, s$1)
          case _          ⇒
        }(ExecutionContexts.sameThreadExecutionContext)
      })
  }

  def getSenderChannel(c: Context): (c.universe.Type, c.Tree, c.Expr[ActorRef]) = {
    val replyChannel = c.inferImplicitValue(c.typeOf[ChannelRef[_]])
    if (!replyChannel.isEmpty) {
      import c.universe._
      replyChannel.tpe match {
        case TypeRef(_, _, param :: Nil) ⇒
          (param, replyChannel, c.Expr(Select(replyChannel, "actorRef"))(c.universe.weakTypeTag[ActorRef]))
      }
    } else abort(c, "no implicit sender ChannelRef found")
  }

  def verify(c: Context)(sender: c.universe.Tree, msgT: c.universe.Type, sndT: c.universe.Type, chT: c.universe.Type)(): Unit = {
    def rec(msg: Set[c.universe.Type], checked: Set[c.universe.Type], depth: Int): Unit =
      if (msg.nonEmpty) {
        val u: c.universe.type = c.universe
        val replies = msg map (m ⇒ m -> replyChannels(u)(chT, m))
        val missing = replies collect { case (k, v) if v.size == 0 ⇒ k }
        if (missing.nonEmpty)
          error(c, s"target ChannelRef does not support messages of types ${missing mkString ", "} (at depth $depth)")
        else {
          val nextSend = replies.map(_._2).flatten map (m ⇒ m -> replyChannels(u)(sndT, m))
          val nextMissing = nextSend collect { case (k, v) if v.size == 0 ⇒ k }
          if (nextMissing.nonEmpty)
            error(c, s"implicit sender `$sender` does not support messages of the reply types ${nextMissing mkString ", "} (at depth $depth)")
          else {
            val nextChecked = checked ++ msg
            val nextMsg = nextSend.map(_._2).flatten -- nextChecked
            rec(nextMsg, nextChecked, depth + 1)
          }
        }
      }
    rec(Set(msgT), Set(c.universe.typeOf[Nothing]), 1)
  }
}