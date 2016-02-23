/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine

import scala.annotation.tailrec
import akka.stream.actor.{ ActorPublisherMessage, ActorPublisher }

/**
 * An actor publisher for producing a simple stream of singleton tokens
 * the release of which is triggered by the reception of a [[TokenSourceActor.Trigger]] message.
 */
// FIXME #16520 move this into streams
private[engine] class TokenSourceActor[T](token: T) extends ActorPublisher[T] {
  private var triggered = 0

  def receive = {
    case TokenSourceActor.Trigger ⇒
      triggered += 1
      tryDispatch()

    case ActorPublisherMessage.Request(_) ⇒
      tryDispatch()

    case ActorPublisherMessage.Cancel ⇒
      context.stop(self)
  }

  @tailrec private def tryDispatch(): Unit =
    if (triggered > 0 && totalDemand > 0) {
      onNext(token)
      triggered -= 1
      tryDispatch()
    }
}

private[engine] object TokenSourceActor {
  case object Trigger
}
