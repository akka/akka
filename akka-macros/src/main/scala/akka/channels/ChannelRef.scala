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

class ChannelRef[+T <: ChannelList](val actorRef: ActorRef) extends AnyVal {

  def ![M](msg: M): Unit = macro ChannelRef.tell[T, M]

  def narrow[C <: ChannelList]: ChannelRef[C] = macro ChannelRef.narrowImpl[C, T]

}

object ChannelRef {
  import Channels._

  def tell[T <: ChannelList: c.WeakTypeTag, M: c.WeakTypeTag](c: Context {
                                                                type PrefixType = ChannelRef[T]
                                                              })(msg: c.Expr[M]): c.Expr[Unit] = {
    val out = replyChannels(c.universe)(c.weakTypeOf[T], c.weakTypeOf[M])
    if (out.isEmpty) {
      c.error(c.enclosingPosition, s"This ChannelRef does not support messages of type ${c.weakTypeOf[M]}")
      return c.universe.reify(())
    }
    val replyChannel = c.inferImplicitValue(c.typeOf[ChannelRef[_]])
    if (!replyChannel.isEmpty) {
      import c.universe._
      val list = replyChannel.tpe match {
        case TypeRef(_, _, param :: Nil) ⇒ param
      }
      val m = missingChannels(c.universe)(list, out) filterNot (_ =:= weakTypeOf[Nothing])
      if (m.isEmpty) {
        val sender = c.Expr[ChannelRef[_]](replyChannel)(c.WeakTypeTag(replyChannel.tpe))
        c.universe.reify(c.prefix.splice.actorRef.tell(msg.splice, sender.splice.actorRef))
      } else {
        c.error(c.enclosingPosition, s"The implicit sender `${replyChannel.symbol}` does not support messages of the reply types ${m.mkString(", ")}")
        c.universe.reify(())
      }
    } else {
      val senderTree = c.inferImplicitValue(c.typeOf[ActorRef])
      val sender =
        if (senderTree.isEmpty) c.universe.reify(Actor.noSender)
        else c.Expr(senderTree)(c.WeakTypeTag(senderTree.tpe))
      c.universe.reify(c.prefix.splice.actorRef.tell(msg.splice, sender.splice))
    }
  }

  def narrowImpl[C <: ChannelList: c.WeakTypeTag, T <: ChannelList: c.WeakTypeTag](
    c: Context {
      type PrefixType = ChannelRef[T]
    }): c.Expr[ChannelRef[C]] = {
    import c.{ universe ⇒ u }
    for (in ← inputChannels(u)(u.weakTypeOf[C])) {
      val replies = replyChannels(u)(u.weakTypeOf[T], in)
      if (replies.isEmpty) c.error(c.enclosingPosition, s"original ChannelRef does not support input type $in")
      else {
        val targetReplies = replyChannels(u)(u.weakTypeOf[C], in)
        val unsatisfied = replies filterNot (r ⇒ targetReplies exists (r <:< _))
        if (unsatisfied.nonEmpty) c.error(c.enclosingPosition, s"reply types ${unsatisfied mkString ", "} not covered for channel $in")
        val leftovers = targetReplies filterNot (t ⇒ replies exists (_ <:< t))
        if (leftovers.nonEmpty) c.error(c.enclosingPosition, s"reply types ${leftovers mkString ", "} are superfluous for channel $in")
      }
    }
    u.reify(c.prefix.splice.asInstanceOf[ChannelRef[C]])
  }

}