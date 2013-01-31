/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.channels

import language.experimental.{ macros ⇒ makkros }
import akka.actor.ActorRef
import scala.reflect.runtime.universe.TypeTag
import scala.reflect.macros.Context
import scala.annotation.tailrec
import scala.reflect.macros.Universe
import akka.actor.Actor
import akka.actor.ActorContext
import scala.concurrent.Future
import akka.util.Timeout
import akka.AkkaException
import scala.util.control.NoStackTrace

case class NarrowingException(errors: String) extends AkkaException(errors) with NoStackTrace

class ChannelRef[+T <: ChannelList](val actorRef: ActorRef) extends AnyVal {

  def <-!-[M](msg: M): M = macro macros.Tell.impl[T, M]

  def <-!-[M](future: Future[M]): Future[M] = macro macros.Tell.futureImpl[T, M]

  def <-?-[M](msg: M): Future[_] = macro macros.Ask.impl[ChannelList, Any, T, M]

  def narrow[C <: ChannelList]: ChannelRef[C] = macro macros.Narrow.impl[C, T]

}
