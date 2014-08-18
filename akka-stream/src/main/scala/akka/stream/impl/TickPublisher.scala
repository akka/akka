/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.actor.{ Actor, ActorRef, Cancellable, Props, SupervisorStrategy }
import akka.stream.MaterializerSettings
import org.reactivestreams.{ Subscriber, Subscription }

import scala.collection.mutable
import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
private[akka] object TickPublisher {
  def props(interval: FiniteDuration, tick: () ⇒ Any, settings: MaterializerSettings): Props =
    Props(new TickPublisher(interval, tick, settings)).withDispatcher(settings.dispatcher)

  private case object Tick
}

/**
 * INTERNAL API
 *
 * Elements are produced from the tick closure periodically with the specified interval.
 * Each subscriber will receive the tick element if it has requested any elements,
 * otherwise the tick element is dropped for that subscriber.
 */
private[akka] class TickPublisher(interval: FiniteDuration, tick: () ⇒ Any, settings: MaterializerSettings) extends Actor with SoftShutdown {
  import akka.stream.impl.TickPublisher._

  var exposedPublisher: LazyPublisherLike[Any] = _
  val demand = mutable.Map.empty[Subscriber[Any], Long]

  override val supervisorStrategy = SupervisorStrategy.stoppingStrategy

  var tickTask: Option[Cancellable] = None

  def receive = {
    case ExposedPublisher(publisher) ⇒
      exposedPublisher = publisher
      val subscribers = exposedPublisher.takeEarlySubscribers(self)
      subscribers foreach {
        case (subscription, demand) ⇒ registerSubscriber(subscription.subscriber, demand)
      }

      context.become(active)
    case _ ⇒ throw new IllegalStateException("The first message must be ExposedPublisher")
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
          demand foreach { case (subscriber, _) ⇒ subscriber.onError(e) }
      }

    case RequestMore(subscription, elements) ⇒
      demand.get(subscription.subscriber) match {
        case Some(d) ⇒ demand(subscription.subscriber) = d + elements
        case None    ⇒ // canceled
      }
    case Cancel(subscription) ⇒ unregisterSubscriber(subscription.subscriber)

    case SubscribePending ⇒
      exposedPublisher.takePendingSubscribers() foreach { sub ⇒ registerSubscriber(sub, initialDemand = 0) }

  }

  def registerSubscriber(subscriber: Subscriber[Any], initialDemand: Long): Unit = {
    if (demand.contains(subscriber))
      subscriber.onError(new IllegalStateException(s"Cannot subscribe $subscriber twice"))
    else {
      demand(subscriber) = initialDemand
    }
  }

  private def unregisterSubscriber(subscriber: Subscriber[Any]): Unit = {
    demand -= subscriber
    if (demand.isEmpty) {
      exposedPublisher.shutdown(LazyActorPublisher.NormalShutdownReason)
      softShutdown()
    }
  }

  override def postStop(): Unit = {
    tickTask.foreach(_.cancel)
    if (exposedPublisher ne null)
      exposedPublisher.shutdown(LazyActorPublisher.NormalShutdownReason)
  }

}

