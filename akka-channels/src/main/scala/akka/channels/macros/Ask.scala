/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

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

  def impl[ //
  ReturnChannels <: ChannelList, // the precise type union describing the reply
  ReturnLUB, // the least-upper bound for the reply types
  Channel <: ChannelList: c.WeakTypeTag, // the channel being asked
  Msg: c.WeakTypeTag // the message being sent down the channel
  ](c: Context {
      type PrefixType = ChannelRef[Channel]
    })(msg: c.Expr[Msg]): c.Expr[Future[WrappedMessage[ReturnChannels, ReturnLUB]]] = {
    import c.universe._

    val tpeChannel = weakTypeOf[Channel]
    val tpeMsg = weakTypeOf[Msg]
    val isFuture = tpeMsg <:< typeOf[Future[_]]
    val unwrapped =
      if (isFuture)
        tpeMsg match {
          case TypeRef(_, _, x :: _) ⇒ unwrapMsgType(c.universe)(x)
        }
      else unwrapMsgType(c.universe)(tpeMsg)
    val out = replyChannels(c.universe)(tpeChannel, unwrapped)

    Tell.verify(c)(null, unwrapped, typeOf[(Any, Nothing) :+: TNil], tpeChannel)

    implicit val ttReturnChannels = c.TypeTag[ReturnChannels](toChannels(c.universe)(out, weakTypeOf[Nothing]))
    implicit val ttReturnLUB = c.TypeTag[ReturnLUB](c.universe.lub(out))
    if (isFuture)
      if (unwrapped <:< typeOf[ChannelList])
        reify(askFutureWrapped[WrappedMessage[ReturnChannels, ReturnLUB]](
          c.prefix.splice.actorRef, msg.splice.asInstanceOf[Future[WrappedMessage[TNil, Any]]])(imp[Timeout](c).splice))
      else
        reify(askFuture[WrappedMessage[ReturnChannels, ReturnLUB]](
          c.prefix.splice.actorRef, msg.splice.asInstanceOf[Future[Any]])(imp[Timeout](c).splice))
    else
      reify(askOps[WrappedMessage[ReturnChannels, ReturnLUB]](
        c.prefix.splice.actorRef, toMsg(c)(msg, tpeMsg).splice)(imp[Timeout](c).splice))
  }

  def opsImpl[ //
  ReturnChannels <: ChannelList, // the precise type union describing the reply
  ReturnLUB, // the least-upper bound for the reply types
  Channel <: ChannelList: c.WeakTypeTag, // the channel being asked
  Msg: c.WeakTypeTag // the message being sent down the channel
  ](c: Context {
      type PrefixType = AnyOps[Msg]
    })(channel: c.Expr[ChannelRef[Channel]]): c.Expr[Future[WrappedMessage[ReturnChannels, ReturnLUB]]] = {
    import c.universe._

    val tpeChannel = weakTypeOf[Channel]
    val tpeMsg = weakTypeOf[Msg]
    val unwrapped = unwrapMsgType(c.universe)(tpeMsg)
    val out = replyChannels(c.universe)(tpeChannel, unwrapped)

    Tell.verify(c)(null, unwrapped, typeOf[(Any, Nothing) :+: TNil], tpeChannel)

    implicit val ttReturnChannels = c.TypeTag[ReturnChannels](toChannels(c.universe)(out, weakTypeOf[Nothing]))
    implicit val ttReturnLUB = c.TypeTag[ReturnLUB](c.universe.lub(out))
    val msg = reify(c.prefix.splice.value)
    reify(askOps[WrappedMessage[ReturnChannels, ReturnLUB]](
      channel.splice.actorRef, toMsg(c)(msg, tpeMsg).splice)(imp[Timeout](c).splice))
  }

  // this is the implementation for Future[_] -?-> ChannelRef[_]
  def futureImpl[ //
  ReturnChannels <: ChannelList, // the precise type union describing the reply
  ReturnLUB, // the least-upper bound for the reply types
  Channel <: ChannelList: c.WeakTypeTag, // the channel being asked
  Msg: c.WeakTypeTag // the message being sent down the channel
  ](c: Context {
      type PrefixType = FutureOps[Msg]
    })(channel: c.Expr[ChannelRef[Channel]]): c.Expr[Future[WrappedMessage[ReturnChannels, ReturnLUB]]] = {
    import c.universe._

    val tpeChannel = weakTypeOf[Channel]
    val tpeMsg = weakTypeOf[Msg]
    val unwrapped = unwrapMsgType(c.universe)(tpeMsg)
    val out = replyChannels(c.universe)(tpeChannel, unwrapped)

    Tell.verify(c)(null, unwrapped, typeOf[(Any, Nothing) :+: TNil], tpeChannel)

    implicit val ttReturnChannels = c.TypeTag[ReturnChannels](toChannels(c.universe)(out, weakTypeOf[Nothing]))
    implicit val ttReturnLUB = c.TypeTag[ReturnLUB](c.universe.lub(out))
    if (tpeMsg <:< typeOf[WrappedMessage[_, _]])
      reify(askFutureWrapped[WrappedMessage[ReturnChannels, ReturnLUB]](
        channel.splice.actorRef, c.prefix.splice.future.asInstanceOf[Future[WrappedMessage[TNil, Any]]])(imp[Timeout](c).splice))
    else
      reify(askFuture[WrappedMessage[ReturnChannels, ReturnLUB]](
        channel.splice.actorRef, c.prefix.splice.future)(imp[Timeout](c).splice))
  }

  val wrapMessage = (m: Any) ⇒ (new WrappedMessage[TNil, Any](m): Any)

  @inline def askOps[T <: WrappedMessage[_, _]](target: ActorRef, msg: Any)(implicit t: Timeout): Future[T] = {
    implicit val ec = ExecutionContexts.sameThreadExecutionContext
    akka.pattern.ask(target, msg).map(wrapMessage).asInstanceOf[Future[T]]
  }

  def askFuture[T <: WrappedMessage[_, _]](target: ActorRef, future: Future[_])(implicit t: Timeout): Future[T] = {
    implicit val ec = ExecutionContexts.sameThreadExecutionContext
    future flatMap (m ⇒ akka.pattern.ask(target, m).map(wrapMessage).asInstanceOf[Future[T]])
  }

  def askFutureWrapped[T <: WrappedMessage[_, _]](target: ActorRef, future: Future[WrappedMessage[_, _]])(implicit t: Timeout): Future[T] = {
    implicit val ec = ExecutionContexts.sameThreadExecutionContext
    future flatMap (w ⇒ akka.pattern.ask(target, w.value).map(wrapMessage).asInstanceOf[Future[T]])
  }

}