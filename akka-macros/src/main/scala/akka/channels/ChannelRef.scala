/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.channels

import language.experimental.macros
import akka.actor.ActorRef
import scala.reflect.runtime.universe.TypeTag
import scala.reflect.macros.Context
import scala.annotation.tailrec
import scala.reflect.macros.Universe
import akka.actor.Actor
import akka.actor.ActorContext
import scala.concurrent.Future
import akka.util.Timeout

class ChannelRef[+T <: ChannelList](val actorRef: ActorRef) extends AnyVal {

  def ![M](msg: M): Unit = macro ChannelRef.tell[T, M]

  def ?[M](msg: M): Future[_] = macro ChannelRef.ask[T, M]

  def forward[M](msg: M): Unit = macro ChannelRef.forward[T, M]

  def narrow[C <: ChannelList]: ChannelRef[C] = macro ChannelRef.narrowImpl[C, T]

}

object ChannelRef {
  import Channels._

  def tell[T <: ChannelList: c.WeakTypeTag, M: c.WeakTypeTag](c: Context {
                                                                type PrefixType = ChannelRef[T]
                                                              })(msg: c.Expr[M]): c.Expr[Unit] = {
    import c.{ universe ⇒ u }

    def getSenderChannel = {
      val replyChannel = c.inferImplicitValue(c.typeOf[ChannelRef[_]])
      if (!replyChannel.isEmpty) {
        import u._
        replyChannel.tpe match {
          case TypeRef(_, _, param :: Nil) ⇒
            Some((param, replyChannel, c.Expr(Select(replyChannel, "actorRef"))(u.weakTypeTag[ActorRef])))
        }
      } else None
    }

    def getSenderRef = {
      val senderTree = c.inferImplicitValue(c.typeOf[ActorRef])
      if (senderTree.isEmpty) {
        val noSender = c.universe.reify(Actor.noSender)
        ((u.typeOf[(Any, Nothing) :+: TNil], noSender.tree, noSender))
      } else ((u.typeOf[(Any, Any) :+: TNil], senderTree, c.Expr(senderTree)(c.WeakTypeTag(senderTree.tpe))))
    }

    val tT = u.weakTypeOf[T]
    val (tS, senderTree, sender) = getSenderChannel getOrElse getSenderRef

    def err(msg: String) = c.error(c.enclosingPosition, msg)

    def verify(msg: Set[u.Type], checked: Set[u.Type], depth: Int): Unit = if (msg.nonEmpty) {
      val replies = msg map (m ⇒ m -> replyChannels(u)(tT, m))
      val missing = replies collect { case (k, v) if v.size == 0 ⇒ k }
      if (missing.nonEmpty)
        err(s"target ChannelRef does not support messages of types ${missing mkString ", "} (at depth $depth)")
      else {
        val nextSend = replies.map(_._2).flatten map (m ⇒ m -> replyChannels(u)(tS, m))
        val nextMissing = nextSend collect { case (k, v) if v.size == 0 ⇒ k }
        if (nextMissing.nonEmpty)
          err(s"implicit sender `$senderTree` does not support messages of the reply types ${nextMissing mkString ", "} (at depth $depth)")
        else {
          val nextChecked = checked ++ msg
          val nextMsg = nextSend.map(_._2).flatten -- nextChecked
          verify(nextMsg, nextChecked, depth + 1)
        }
      }
    }

    verify(Set(u.weakTypeOf[M]), Set(u.typeOf[Nothing]), 1)
    u.reify(c.prefix.splice.actorRef.tell(msg.splice, sender.splice))
  }

  def ask[T <: ChannelList: c.WeakTypeTag, M: c.WeakTypeTag](c: Context {
                                                               type PrefixType = ChannelRef[T]
                                                             })(msg: c.Expr[M]): c.Expr[Future[_]] = {
    import c.universe._

    val out = replyChannels(c.universe)(weakTypeOf[T], weakTypeOf[M])
    if (out.isEmpty) {
      c.error(c.enclosingPosition, s"This ChannelRef does not support messages of type ${weakTypeOf[M]}")
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
              c.prefix.splice.actorRef, msg.splice)(
                c.Expr(timeout)(weakTypeTag[Timeout]).splice)).tree,
            "asInstanceOf"),
          List(TypeTree().setType(result))))
    }
  }

  def forward[T <: ChannelList: c.WeakTypeTag, M: c.WeakTypeTag](c: Context {
                                                                   type PrefixType = ChannelRef[T]
                                                                 })(msg: c.Expr[M]): c.Expr[Unit] = {
    import c.universe._
    if (weakTypeOf[M] =:= weakTypeOf[Values.WrappedMessage[T]]) {
      reify(
        c.prefix.splice.actorRef.forward(
          msg.splice.asInstanceOf[Values.WrappedMessage[_]].value)(
            c.Expr(Ident("implicitly"))(weakTypeTag[ActorContext]).splice))
    } else {
      c.error(c.enclosingPosition, s"cannot forward message unless types match exactly")
      reify(())
    }
  }

  def narrowImpl[C <: ChannelList: c.WeakTypeTag, T <: ChannelList: c.WeakTypeTag](
    c: Context {
      type PrefixType = ChannelRef[T]
    }): c.Expr[ChannelRef[C]] = {
    import c.{ universe ⇒ u }
    narrowCheck(u)(u.weakTypeOf[T], u.weakTypeOf[C]) match {
      case Nil        ⇒ // okay
      case err :: Nil ⇒ c.error(c.enclosingPosition, err)
      case list       ⇒ c.error(c.enclosingPosition, list mkString ("multiple errors:\n  - ", "\n  - ", ""))
    }
    u.reify(c.prefix.splice.asInstanceOf[ChannelRef[C]])
  }

}