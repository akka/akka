/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.actor.{ Actor, ActorRef, Cancellable, Props, SupervisorStrategy }
import akka.stream.{ MaterializerSettings, ReactiveStreamsConstants }
import org.reactivestreams.{ Subscriber, Subscription }
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal
import java.util.concurrent.atomic.AtomicBoolean

/**
 * INTERNAL API
 */
private[akka] object TickPublisher {
  def props(initialDelay: FiniteDuration, interval: FiniteDuration, tick: () ⇒ Any,
            cancelled: AtomicBoolean, settings: MaterializerSettings): Props =
    Props(new TickPublisher(initialDelay, interval, tick, cancelled, settings)).
      withDispatcher(settings.dispatcher)

  object TickPublisherSubscription {
    case class Cancel(subscriber: Subscriber[_ >: Any])
    case class RequestMore(elements: Long, subscriber: Subscriber[_ >: Any])
  }

  class TickPublisherSubscription(ref: ActorRef, subscriber: Subscriber[_ >: Any]) extends Subscription {
    import akka.stream.impl.TickPublisher.TickPublisherSubscription._
    def cancel(): Unit = ref ! Cancel(subscriber)
    def request(elements: Long): Unit =
      if (elements <= 0) throw new IllegalArgumentException(ReactiveStreamsConstants.NumberOfElementsInRequestMustBePositiveMsg)
      else ref ! RequestMore(elements, subscriber)
    override def toString = "TickPublisherSubscription"
  }

  private case object Tick
}

/**
 * INTERNAL API
 *
 * Elements are produced from the tick closure periodically with the specified interval.
 * Each subscriber will receive the tick element if it has requested any elements,
 * otherwise the tick element is dropped for that subscriber.
 */
private[akka] class TickPublisher(initialDelay: FiniteDuration, interval: FiniteDuration, tick: () ⇒ Any,
                                  cancelled: AtomicBoolean, settings: MaterializerSettings) extends Actor with SoftShutdown {
  import akka.stream.impl.TickPublisher.TickPublisherSubscription._
  import akka.stream.impl.TickPublisher._

  var exposedPublisher: ActorPublisher[Any] = _
  val demand = mutable.Map.empty[Subscriber[_ >: Any], Long]
  var failed = false

  override val supervisorStrategy = SupervisorStrategy.stoppingStrategy

  var tickTask: Option[Cancellable] = None

  def receive = {
    case ExposedPublisher(publisher) ⇒
      exposedPublisher = publisher
      context.become(waitingForFirstSubscriber)
    case _ ⇒ throw new IllegalStateException("The first message must be ExposedPublisher")
  }

  def waitingForFirstSubscriber: Receive = {
    case SubscribePending ⇒
      exposedPublisher.takePendingSubscribers() foreach registerSubscriber
      import context.dispatcher
      tickTask = Some(context.system.scheduler.schedule(initialDelay, interval, self, Tick))
      context.become(active)
  }

  def active: Receive = {
    case Tick ⇒
      try {
        val tickElement = tick()
        demand foreach {
          case (subscriber, d) ⇒
            if (d > 0) {
              demand(subscriber) = d - 1
              subscriber.onNext(tickElement)
            }
        }
      } catch {
        case NonFatal(e) ⇒
          // tick closure throwed => onError downstream
          failed = true
          demand foreach { case (subscriber, _) ⇒ subscriber.onError(e) }
          softShutdown()
      }

    case RequestMore(elements, subscriber) ⇒
      demand.get(subscriber) match {
        case Some(d) ⇒ demand(subscriber) = d + elements
        case None    ⇒ // canceled
      }
    case Cancel(subscriber) ⇒ unregisterSubscriber(subscriber)

    case SubscribePending ⇒
      exposedPublisher.takePendingSubscribers() foreach registerSubscriber

  }

  def registerSubscriber(subscriber: Subscriber[_ >: Any]): Unit = {
    if (demand.contains(subscriber))
      subscriber.onError(new IllegalStateException(s"${getClass.getSimpleName} [$self, sub: $subscriber] ${ReactiveStreamsConstants.CanNotSubscribeTheSameSubscriberMultipleTimes}"))
    else {
      val subscription = new TickPublisherSubscription(self, subscriber)
      demand(subscriber) = 0
      subscriber.onSubscribe(subscription)
    }
  }

  private def unregisterSubscriber(subscriber: Subscriber[_ >: Any]): Unit = {
    demand -= subscriber
    if (demand.isEmpty) {
      exposedPublisher.shutdown(ActorPublisher.NormalShutdownReason)
      softShutdown()
    }
  }

  override def postStop(): Unit = {
    tickTask.foreach(_.cancel)
    cancelled.set(true)
    if (exposedPublisher ne null)
      exposedPublisher.shutdown(ActorPublisher.NormalShutdownReason)
    if (!failed)
      demand.foreach {
        case (subscriber, _) ⇒ subscriber.onComplete()
      }
  }

}

