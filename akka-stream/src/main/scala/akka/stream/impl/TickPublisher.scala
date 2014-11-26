/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.{ Actor, ActorRef, Cancellable, Props, SupervisorStrategy }
import akka.stream.MaterializerSettings
import org.reactivestreams.{ Subscriber, Subscription }

import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
private[akka] object TickPublisher {
  def props(initialDelay: FiniteDuration, interval: FiniteDuration, tick: () ⇒ Any,
            settings: MaterializerSettings, cancelled: AtomicBoolean): Props =
    Props(new TickPublisher(initialDelay, interval, tick, settings, cancelled)).withDispatcher(settings.dispatcher)

  object TickPublisherSubscription {
    case object Cancel
    case class RequestMore(elements: Long)
  }

  class TickPublisherSubscription(ref: ActorRef) extends Subscription {
    import akka.stream.impl.TickPublisher.TickPublisherSubscription._
    def cancel(): Unit = ref ! Cancel
    def request(elements: Long): Unit =
      if (elements <= 0) throw new IllegalArgumentException(ReactiveStreamsCompliance.NumberOfElementsInRequestMustBePositiveMsg)
      else ref ! RequestMore(elements)
    override def toString = "TickPublisherSubscription"
  }

  private case object Tick
}

/**
 * INTERNAL API
 *
 * Elements are produced from the tick closure periodically with the specified interval. Supports only one subscriber.
 * The subscriber will receive the tick element if it has requested any elements,
 * otherwise the tick element is dropped.
 */
private[akka] class TickPublisher(initialDelay: FiniteDuration, interval: FiniteDuration, tick: () ⇒ Any,
                                  settings: MaterializerSettings, cancelled: AtomicBoolean) extends Actor with SoftShutdown {
  import akka.stream.impl.TickPublisher.TickPublisherSubscription._
  import akka.stream.impl.TickPublisher._

  var exposedPublisher: ActorPublisher[Any] = _
  private var subscriber: Subscriber[_ >: Any] = null
  private var demand: Long = 0

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
        if (demand > 0) {
          demand -= 1
          subscriber.onNext(tickElement)
        }
      } catch {
        case NonFatal(e) ⇒
          if (subscriber ne null) {
            subscriber.onError(e)
            subscriber = null
          }
          exposedPublisher.shutdown(Some(e))
          context.stop(self)
      }

    case RequestMore(elements) ⇒
      demand += elements
      if (demand < 0) {
        // Long has overflown, reactive-streams specification rule 3.17
        exposedPublisher.shutdown(Some(
          new IllegalStateException(ReactiveStreamsCompliance.TotalPendingDemandMustNotExceedLongMaxValue)))
        context.stop(self)
      }

    case Cancel ⇒
      subscriber = null
      context.stop(self)

    case SubscribePending ⇒
      exposedPublisher.takePendingSubscribers() foreach registerSubscriber

  }

  def registerSubscriber(s: Subscriber[_ >: Any]): Unit = {
    if (subscriber ne null) s.onError(new IllegalStateException(s"${getClass.getSimpleName} [$self, sub: $subscriber] ${ReactiveStreamsCompliance.CanNotSubscribeTheSameSubscriberMultipleTimes}"))
    else {
      val subscription = new TickPublisherSubscription(self)
      subscriber = s
      subscriber.onSubscribe(subscription)
    }
  }

  override def postStop(): Unit = {
    tickTask.foreach(_.cancel)
    cancelled.set(true)
    if (subscriber ne null) subscriber.onComplete()
    if (exposedPublisher ne null)
      exposedPublisher.shutdown(ActorPublisher.NormalShutdownReason)
  }

}

