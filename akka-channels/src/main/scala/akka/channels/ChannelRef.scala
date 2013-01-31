/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.channels

import language.experimental.{ macros ⇒ makkros }
import akka.actor.ActorRef
import scala.concurrent.Future

/**
 * A channel reference, holding a type list of all channels supported by the
 * underlying actor. This actor’s reference can be obtained as the `actorRef`
 * member.
 */
class ChannelRef[+T <: ChannelList](val actorRef: ActorRef) extends AnyVal {

  /**
   * Send a message over this channel, “tell” semantics, returning the message.
   */
  def <-!-[M](msg: M): M = macro macros.Tell.impl[T, M]

  /**
   * Eventually send the value contained in the future over this channel,
   * “tell” semantics, returning a Future which is completed after sending
   * with the value which was sent (“Future.andThen” semantics).
   */
  def <-!-[M](future: Future[M]): Future[M] = macro macros.Tell.futureImpl[T, M]

  /**
   * Send a message over this channel, “ask” semantics, returning a Future
   * which will be completed with the reply message or a TimeoutException.
   * If the message is a Future itself, eventually send the Future’s value.
   */
  def <-?-[M](msg: M): Future[_] = macro macros.Ask.impl[ChannelList, Any, T, M]

  /**
   * Narrow this ChannelRef by removing channels or narrowing input types or
   * widening output types.
   */
  def narrow[C <: ChannelList]: ChannelRef[C] = macro macros.Narrow.impl[C, T]

}
