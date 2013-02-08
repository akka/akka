/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

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

  def impl[MyChannels <: ChannelList: c.WeakTypeTag, Msg: c.WeakTypeTag](c: Context {
                                                                           type PrefixType = ChannelRef[MyChannels]
                                                                         })(msg: c.Expr[Msg]): c.Expr[Msg] =
    doTell(c)(c.universe.weakTypeOf[MyChannels], c.universe.weakTypeOf[Msg], msg, c.prefix)

  def opsImpl[MyChannels <: ChannelList: c.WeakTypeTag, Msg: c.WeakTypeTag](c: Context {
                                                                              type PrefixType = AnyOps[Msg]
                                                                            })(channel: c.Expr[ChannelRef[MyChannels]]): c.Expr[Msg] = {
    import c.universe._
    doTell(c)(weakTypeOf[MyChannels], weakTypeOf[Msg], reify(c.prefix.splice.value), channel)
  }

  def doTell[MyChannels <: ChannelList: c.WeakTypeTag, Msg: c.WeakTypeTag](c: Context)(
    tpeMyChannels: c.Type, tpeMsg: c.Type, msg: c.Expr[Msg], target: c.Expr[ChannelRef[MyChannels]]): c.Expr[Msg] = {
    val (tpeSender, senderTree, sender) = getSenderChannel(c)
    verify(c)(senderTree, unwrapMsgType(c.universe)(tpeMsg), tpeSender, tpeMyChannels)
    val cond = bool(c, tpeMsg <:< c.typeOf[WrappedMessage[_, _]])
    c.universe.reify {
      val $m = msg.splice
      target.splice.actorRef.tell(if (cond.splice) $m.asInstanceOf[WrappedMessage[TNil, Any]].value else $m, sender.splice)
      $m
    }
  }

  def futureOpsImpl[MyChannels <: ChannelList: c.WeakTypeTag, Msg: c.WeakTypeTag](c: Context {
                                                                                    type PrefixType = FutureOps[Msg]
                                                                                  })(channel: c.Expr[ChannelRef[MyChannels]]): c.Expr[Future[Msg]] = {
    import c.universe._
    doFutureTell(c)(weakTypeOf[MyChannels], weakTypeOf[Msg], reify(c.prefix.splice.future), channel)
  }

  def futureImpl[MyChannels <: ChannelList: c.WeakTypeTag, Msg: c.WeakTypeTag](c: Context {
                                                                                 type PrefixType = ChannelRef[MyChannels]
                                                                               })(future: c.Expr[Future[Msg]]): c.Expr[Future[Msg]] = {
    import c.universe._
    doFutureTell(c)(weakTypeOf[MyChannels], weakTypeOf[Msg], future, c.prefix)
  }

  def doFutureTell[MyChannels <: ChannelList: c.WeakTypeTag, Msg: c.WeakTypeTag](c: Context)(
    tpeMyChannels: c.Type, tpeMsg: c.Type, future: c.Expr[Future[Msg]], target: c.Expr[ChannelRef[MyChannels]]): c.Expr[Future[Msg]] = {
    val (tpeSender, senderTree, sender) = getSenderChannel(c)
    verify(c)(senderTree, unwrapMsgType(c.universe)(tpeMsg), tpeSender, tpeMyChannels)
    c.universe.reify(pipeTo[Msg](future.splice, target.splice, sender.splice))
  }

  @inline def pipeTo[Msg](f: Future[Msg], c: ChannelRef[_], snd: ActorRef): Future[Msg] =
    f.future.andThen {
      case Success(s: WrappedMessage[_, _]) ⇒ c.actorRef.tell(s.value, snd)
      case Success(s)                       ⇒ c.actorRef.tell(s, snd)
      case _                                ⇒
    }(ExecutionContexts.sameThreadExecutionContext)

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
    val unknown = c.universe.weakTypeOf[UnknownDoNotWriteMeDown]
    val nothing = c.universe.weakTypeOf[Nothing]
    def ignoreUnknown(in: c.universe.Type): c.universe.Type = if (in =:= unknown) nothing else in
    def rec(msg: Set[c.universe.Type], checked: Set[c.universe.Type], depth: Int): Unit =
      if (msg.nonEmpty) {
        val u: c.universe.type = c.universe
        val replies = msg map (m ⇒ m -> (replyChannels(u)(chT, m) map (t ⇒ ignoreUnknown(t))))
        val missing = replies collect { case (k, v) if v.size == 0 ⇒ k }
        if (missing.nonEmpty)
          error(c, s"target ChannelRef does not support messages of types ${missing mkString ", "} (at depth $depth)")
        else {
          val nextSend = replies.map(_._2).flatten map (m ⇒ m -> (replyChannels(u)(sndT, m) map (t ⇒ ignoreUnknown(t))))
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
    rec(inputChannels(c.universe)(msgT).toSet, Set(c.universe.typeOf[Nothing]), 1)
  }
}