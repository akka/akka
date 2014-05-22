/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import org.reactivestreams.spi.Subscriber
import org.reactivestreams.spi.Subscription
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.SupervisorStrategy
import akka.stream.MaterializerSettings
import scala.util.control.NonFatal
import akka.actor.Cancellable

/**
 * INTERNAL API
 */
private[akka] object TickProducer {
  def props(interval: FiniteDuration, tick: () ⇒ Any, settings: MaterializerSettings): Props =
    Props(new TickProducer(interval, tick, settings)).withDispatcher(settings.dispatcher)

  object TickProducerSubscription {
    case class Cancel(subscriber: Subscriber[Any])
    case class RequestMore(elements: Int, subscriber: Subscriber[Any])
  }

  class TickProducerSubscription(ref: ActorRef, subscriber: Subscriber[Any])
    extends Subscription {
    import TickProducerSubscription._
    def cancel(): Unit = ref ! Cancel(subscriber)
    def requestMore(elements: Int): Unit =
      if (elements <= 0) throw new IllegalArgumentException("The number of requested elements must be > 0")
      else ref ! RequestMore(elements, subscriber)
    override def toString = "TickProducerSubscription"
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
private[akka] class TickProducer(interval: FiniteDuration, tick: () ⇒ Any, settings: MaterializerSettings) extends Actor with SoftShutdown {
  import TickProducer._
  import TickProducer.TickProducerSubscription._

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
    case Cancel ⇒
      softShutdown()

    case SubscribePending ⇒
      exposedPublisher.takePendingSubscribers() foreach registerSubscriber

  }

  def registerSubscriber(subscriber: Subscriber[Any]): Unit = {
    if (demand.contains(subscriber))
      subscriber.onError(new IllegalStateException(s"Cannot subscribe $subscriber twice"))
    else {
      val subscription = new TickProducerSubscription(self, subscriber)
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

