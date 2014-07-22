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

  object TickPublisherSubscription {
    case class Cancel(subscriber: Subscriber[Any])
    case class RequestMore(elements: Int, subscriber: Subscriber[Any])
  }

  class TickPublisherSubscription(ref: ActorRef, subscriber: Subscriber[Any]) extends Subscription {
    import akka.stream.impl.TickPublisher.TickPublisherSubscription._
    def cancel(): Unit = ref ! Cancel(subscriber)
    def request(elements: Int): Unit =
      if (elements <= 0) throw new IllegalArgumentException("The number of requested elements must be > 0")
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
private[akka] class TickPublisher(interval: FiniteDuration, tick: () ⇒ Any, settings: MaterializerSettings) extends Actor with SoftShutdown {
  import akka.stream.impl.TickPublisher.TickPublisherSubscription._
  import akka.stream.impl.TickPublisher._

  var exposedPublisher: ActorPublisher[Any] = _
  val demand = mutable.Map.empty[Subscriber[Any], Long]

  override val supervisorStrategy = SupervisorStrategy.stoppingStrategy

  var tickTask: Option[Cancellable] = None

  def receive = {
    case ExposedPublisher(publisher) ⇒
      exposedPublisher = publisher
      context.setReceiveTimeout(settings.downstreamSubscriptionTimeout)
      context.become(waitingForFirstSubscriber)
    case _ ⇒ throw new IllegalStateException("The first message must be ExposedPublisher")
  }

  def waitingForFirstSubscriber: Receive = {
    case SubscribePending ⇒
      exposedPublisher.takePendingSubscribers() foreach registerSubscriber
      context.setReceiveTimeout(Duration.Undefined)
      import context.dispatcher
      tickTask = Some(context.system.scheduler.schedule(interval, interval, self, Tick))
      context.become(active)
  }

  def active: Receive = {
    case Tick ⇒
      ActorBasedFlowMaterializer.withCtx(context) {
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

  def registerSubscriber(subscriber: Subscriber[Any]): Unit = {
    if (demand.contains(subscriber))
      subscriber.onError(new IllegalStateException(s"Cannot subscribe $subscriber twice"))
    else {
      val subscription = new TickPublisherSubscription(self, subscriber)
      demand(subscriber) = 0
      subscriber.onSubscribe(subscription)
    }
  }

  private def unregisterSubscriber(subscriber: Subscriber[Any]): Unit = {
    demand -= subscriber
    if (demand.isEmpty) {
      exposedPublisher.shutdown(ActorPublisher.NormalShutdownReason)
      softShutdown()
    }
  }

  override def postStop(): Unit = {
    tickTask.foreach(_.cancel)
    if (exposedPublisher ne null)
      exposedPublisher.shutdown(ActorPublisher.NormalShutdownReason)
  }

}

