/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import java.util.concurrent.atomic.AtomicBoolean
import akka.actor.{ Actor, ActorRef, Cancellable, Props, SupervisorStrategy }
import akka.stream.ActorFlowMaterializerSettings
import org.reactivestreams.{ Subscriber, Subscription }
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal
import akka.actor.DeadLetterSuppression

/**
 * INTERNAL API
 */
private[akka] object TickPublisher {
  def props(initialDelay: FiniteDuration, interval: FiniteDuration, tick: Any,
            settings: ActorFlowMaterializerSettings, cancelled: AtomicBoolean): Props =
    Props(new TickPublisher(initialDelay, interval, tick, settings, cancelled)).withDispatcher(settings.dispatcher)

  object TickPublisherSubscription {
    case object Cancel extends DeadLetterSuppression
    final case class RequestMore(elements: Long) extends DeadLetterSuppression
  }

  class TickPublisherSubscription(ref: ActorRef) extends Subscription {
    import akka.stream.impl.TickPublisher.TickPublisherSubscription._
    def cancel(): Unit = ref ! Cancel
    def request(elements: Long): Unit = ref ! RequestMore(elements)
    override def toString = "TickPublisherSubscription"
  }

  private case object Tick
}

/**
 * INTERNAL API
 *
 * Elements are emitted with the specified interval. Supports only one subscriber.
 * The subscriber will receive the tick element if it has requested any elements,
 * otherwise the tick element is dropped.
 */
private[akka] class TickPublisher(initialDelay: FiniteDuration, interval: FiniteDuration, tick: Any,
                                  settings: ActorFlowMaterializerSettings, cancelled: AtomicBoolean) extends Actor with SoftShutdown {
  import akka.stream.impl.TickPublisher.TickPublisherSubscription._
  import akka.stream.impl.TickPublisher._
  import ReactiveStreamsCompliance._

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

  def handleError(error: Throwable): Unit = {
    try {
      if (!error.isInstanceOf[SpecViolation])
        tryOnError(subscriber, error)
    } finally {
      subscriber = null
      exposedPublisher.shutdown(Some(error)) // FIXME should this not be SupportsOnlyASingleSubscriber?
      context.stop(self)
    }
  }

  def active: Receive = {
    case Tick ⇒
      try {
        if (demand > 0) {
          demand -= 1
          tryOnNext(subscriber, tick)
        }
      } catch {
        case NonFatal(e) ⇒ handleError(e)
      }

    case RequestMore(elements) ⇒
      if (elements < 1) {
        handleError(numberOfElementsInRequestMustBePositiveException)
      } else {
        demand += elements
        if (demand < 0) // Long has overflown, reactive-streams specification rule 3.17
          handleError(totalPendingDemandMustNotExceedLongMaxValueException)
      }

    case Cancel ⇒
      subscriber = null
      context.stop(self)

    case SubscribePending ⇒
      exposedPublisher.takePendingSubscribers() foreach registerSubscriber
  }

  def registerSubscriber(s: Subscriber[_ >: Any]): Unit = subscriber match {
    case null ⇒
      val subscription = new TickPublisherSubscription(self)
      subscriber = s
      tryOnSubscribe(s, subscription)
    case _ ⇒
      rejectAdditionalSubscriber(s, exposedPublisher)
  }

  override def postStop(): Unit = {
    tickTask.foreach(_.cancel)
    cancelled.set(true)
    if (subscriber ne null) tryOnComplete(subscriber)
    if (exposedPublisher ne null)
      exposedPublisher.shutdown(ActorPublisher.NormalShutdownReason)
  }
}

