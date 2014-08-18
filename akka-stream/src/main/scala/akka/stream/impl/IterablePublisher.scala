/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.actor.{ Actor, Props }
import akka.stream.MaterializerSettings
import org.reactivestreams.Subscriber

import scala.annotation.tailrec
import scala.collection.immutable
import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
private[akka] object IterablePublisher {
  def props(iterable: immutable.Iterable[Any], settings: MaterializerSettings): Props =
    //Props(new IterablePublisher(iterable, settings)).withDispatcher(settings.dispatcher)
    Props(new IterablePublisherWorker(iterable.iterator, settings.maximumInputBufferSize)).withDispatcher(settings.dispatcher)
}

/**
 * INTERNAL API
 */
private[akka] object IterablePublisherWorker {
  def props(iterator: Iterator[Any], maxPush: Int): Props =
    Props(new IterablePublisherWorker(iterator, maxPush))

  private object PushMore
  case object Finished
}

/**
 * INTERNAL API
 *
 * Each subscriber is served by this worker actor. It pushes elements to the
 * subscriber immediately when it receives demand, but to allow cancel before
 * pushing everything it sends a PushMore to itself after a batch of elements.
 */
private[akka] class IterablePublisherWorker(iterator: Iterator[Any], maxPush: Int)
  extends Actor with SoftShutdown {
  import akka.stream.impl.IterablePublisherWorker._

  require(iterator.hasNext, "Iterator must not be empty")

  var subscriber: Subscriber[Any] = _
  var demand = 0L

  def receive = {
    case ExposedPublisher(publisher) ⇒
      val subscribers: Map[LazySubscription[Any], Int] = publisher.takeEarlySubscribers(self)
      assert(subscribers.size == 1, "A new IterablePublisherWorker must be created for each individual subscriber, but this was shared.")
      subscribers foreach {
        case (subscription, d) ⇒
          subscriber = subscription.subscriber
          demand = d
      }
      push()
    case RequestMore(_, elements) ⇒
      demand += elements
      push()
    case PushMore ⇒
      push()
    case Cancel(_) ⇒
      softShutdown()
  }

  private def push(): Unit = {
    @tailrec def doPush(n: Int): Unit =
      if (demand > 0) {
        demand -= 1
        val hasNext = {
          subscriber.onNext(iterator.next())
          iterator.hasNext
        }
        if (!hasNext) {
          subscriber.onComplete()
          softShutdown()
        } else if (n == 0 && demand > 0)
          self ! PushMore
        else
          doPush(n - 1)
      }

    try doPush(maxPush) catch {
      case NonFatal(e) ⇒
        subscriber.onError(e)
        softShutdown()
    }
  }
}

