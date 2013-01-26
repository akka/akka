package akka.channels

import language.experimental.{ macros ⇒ makkros }
import akka.actor.ActorRef
import akka.util.Timeout
import akka.pattern.ask
import scala.concurrent.{ ExecutionContext, Future }
import scala.reflect.runtime.{ universe ⇒ ru }
import scala.util.Success
import akka.dispatch.ExecutionContexts

sealed trait ChannelList
sealed trait TNil extends ChannelList
sealed trait :+:[A <: (_, _), B <: ChannelList] extends ChannelList

class ActorRefOps(val ref: ActorRef) extends AnyVal {
  import macros.Helpers._
  def narrow[C <: ChannelList](implicit timeout: Timeout, ec: ExecutionContext, tt: ru.TypeTag[C]): Future[ChannelRef[C]] = {
    import Channels._
    ref ? CheckType(tt) map {
      case CheckTypeACK        ⇒ new ChannelRef[C](ref)
      case CheckTypeNAK(error) ⇒ throw NarrowingException(error)
    }
  }
}

class FutureOps[T](val future: Future[T]) extends AnyVal {
  def -!->[C <: ChannelList](channel: ChannelRef[C]): Future[T] = macro macros.Tell.futureImpl[C, T]
  def -?->[C <: ChannelList](channel: ChannelRef[C]): Future[_] = macro macros.Ask.futureImpl[ChannelList, Any, C, T]
  def lub[LUB](implicit ev: T <:< WrappedMessage[_, LUB]): Future[LUB] = {
    implicit val ec = ExecutionContexts.sameThreadExecutionContext
    future map (ev(_).value)
  }
}

class AnyOps[T](val value: T) extends AnyVal {
  def -!->[C <: ChannelList](channel: ChannelRef[C]): Unit = macro macros.Tell.opsImpl[C, T]
  def -?->[C <: ChannelList](channel: ChannelRef[C]): Future[_] = macro macros.Ask.opsImpl[ChannelList, Any, C, T]
}

class WrappedMessage[T <: ChannelList, LUB](val value: LUB) extends AnyVal
