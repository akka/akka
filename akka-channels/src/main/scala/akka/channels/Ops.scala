/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.channels

import language.experimental.{ macros ⇒ makkros }
import akka.actor.ActorRef
import akka.util.Timeout
import akka.pattern.ask
import scala.concurrent.{ ExecutionContext, Future }
import scala.reflect.runtime.{ universe ⇒ ru }
import scala.util.Success
import akka.dispatch.ExecutionContexts
import scala.util.control.NoStackTrace

sealed trait ChannelList
sealed trait TNil extends ChannelList
sealed trait :+:[A <: (_, _), B <: ChannelList] extends ChannelList
sealed trait ReplyChannels[T <: ChannelList] extends ChannelList

/**
 * This type is used to stand in for the unknown reply types of the fabricated
 * sender references; users don’t need to write it down, and if they do, they
 * know that they’re cheating (since these ref types must not escape their
 * defining actor context).
 */
sealed trait UnknownDoNotWriteMeDown

/**
 * This exception is used to signal errors when trying to `.narrow` an
 * ActorRef into a ChannelRef: if the actor finds the requested channel types
 * incompatible with its selfChannel, it will return errors in the same format
 * as would occur during compilation of a `ChannelRef.narrow` operation.
 */
case class NarrowingException(message: String) extends akka.AkkaException(message) with NoStackTrace

class ActorRefOps(val ref: ActorRef) extends AnyVal {
  import macros.Helpers._

  /**
   * Send a query to the actor and check whether it supports the requested
   * channel types; the normal timeout semantics of the `ask` pattern apply.
   * The Future will be completed either with the desired ChannelRef or with
   * an exception (TimeoutException or NarrowingException).
   */
  def narrow[C <: ChannelList](implicit timeout: Timeout, ec: ExecutionContext, tt: ru.TypeTag[C]): Future[ChannelRef[C]] = {
    import Channels._
    ref ? CheckType(tt) map {
      case CheckTypeACK        ⇒ new ChannelRef[C](ref)
      case CheckTypeNAK(error) ⇒ throw NarrowingException(error)
    }
  }
}

class FutureOps[T](val future: Future[T]) extends AnyVal {
  def -!->[C <: ChannelList](channel: ChannelRef[C]): Future[T] = macro macros.Tell.futureOpsImpl[C, T]
  def -?->[C <: ChannelList](channel: ChannelRef[C]): Future[_] = macro macros.Ask.futureImpl[ChannelList, Any, C, T]
  def -*->[U](f: Future[T] ⇒ Future[U]): Future[U] = f(future)
  def lub[LUB](implicit ev: T <:< WrappedMessage[_, LUB]): Future[LUB] = {
    implicit val ec = ExecutionContexts.sameThreadExecutionContext
    future map (ev(_).value)
  }
}

class AnyOps[T](val value: T) extends AnyVal {
  def -!->[C <: ChannelList](channel: ChannelRef[C]): T = macro macros.Tell.opsImpl[C, T]
  def -?->[C <: ChannelList](channel: ChannelRef[C]): Future[_] = macro macros.Ask.opsImpl[ChannelList, Any, C, T]
}

class WrappedMessage[T <: ChannelList, LUB](val value: LUB) extends AnyVal
