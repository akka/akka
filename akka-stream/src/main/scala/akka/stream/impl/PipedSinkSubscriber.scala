/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.actor.{ ActorLogging, Props }
import akka.stream.actor.ActorPublisher.Internal.Subscribe
import akka.stream.actor.ActorSubscriberMessage.{ OnComplete, OnError, OnNext }
import akka.stream.actor._
import org.reactivestreams.Subscriber

private[akka] object PipedSinkSubscriber {
  def props() = Props(new PipedSinkSubscriber())
}

/**
 * INTERNAL API
 */
private[akka] class PipedSinkSubscriber() extends ActorSubscriber with ActorLogging {
  private var pipedSubscriber: Option[Subscriber[Any]] = None

  override val requestStrategy = ZeroRequestStrategy

  def receive = {
    case Subscribe(sub) ⇒
      pipedSubscriber = Some(sub)
      sub.onSubscribe(new ActorPublisherSubscription(self))

    case ActorPublisherMessage.Request(n) ⇒ request(n)
    case ActorPublisherMessage.Cancel     ⇒ cancel()

    case OnNext(elem) ⇒
      runWithSubscriber(_.onNext(elem))
    case OnError(cause) ⇒
      runWithSubscriber(_.onError(cause))
      context.stop(self)
    case OnComplete ⇒
      runWithSubscriber(_.onComplete())
      context.stop(self)
  }

  def runWithSubscriber(f: Subscriber[Any] ⇒ Unit): Unit = {
    pipedSubscriber match {
      case Some(sub) ⇒ f(sub)
      case None ⇒
        log.error("PipedSinkSubscriber has no subscription to piped stream. Cancelling")
        cancel()
    }
  }

}

