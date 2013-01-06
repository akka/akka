/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

import language.implicitConversions
import akka.actor.ActorRef

package object channels {
  implicit def actorRef2Ops(ref: ActorRef) = new Values.ActorRefOps(ref)
}

package channels {
  import akka.util.Timeout
  import akka.pattern.ask
  import scala.concurrent.{ ExecutionContext, Future }
  import scala.reflect.runtime.{ universe ⇒ ru }

  sealed trait ChannelList
  sealed trait TNil extends ChannelList
  sealed trait :+:[A <: (_, _), B <: ChannelList] extends ChannelList

  object Values {
    class ActorRefOps(val ref: ActorRef) extends AnyVal {
      def narrow[C <: ChannelList](implicit timeout: Timeout, ec: ExecutionContext, tt: ru.TypeTag[C]): Future[ChannelRef[C]] = {
        import Channels._
        ref ? CheckType(tt) map {
          case CheckTypeACK        ⇒ new ChannelRef[C](ref)
          case CheckTypeNAK(error) ⇒ throw NarrowingException(error)
        }
      }
    }

    class WrappedMessage[T <: ChannelList](val value: Any) extends AnyVal
  }
}