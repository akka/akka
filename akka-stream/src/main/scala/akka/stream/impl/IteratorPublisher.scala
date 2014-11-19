/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import scala.annotation.tailrec
import scala.util.control.NonFatal
import akka.actor.Actor
import akka.actor.Props
import akka.event.Logging
import akka.stream.MaterializerSettings
import akka.stream.ReactiveStreamsConstants
import org.reactivestreams.Subscriber

/**
 * INTERNAL API
 */
private[akka] object IteratorPublisher {
  def props(iterator: Iterator[Any], settings: MaterializerSettings): Props =
    Props(new IteratorPublisher(iterator, settings)).withDispatcher(settings.dispatcher)

  private case object PushMore

  private sealed trait State
  private sealed trait StopState extends State
  private case object Unitialized extends State
  private case object Initialized extends State
  private case object Cancelled extends StopState
  private case object Completed extends StopState
  private case class Errored(cause: Throwable) extends StopState
}

/**
 * INTERNAL API
 * Elements are produced from the iterator.
 */
private[akka] class IteratorPublisher(iterator: Iterator[Any], settings: MaterializerSettings) extends Actor {
  import IteratorPublisher._
  import ReactiveStreamsConstants._

  private var exposedPublisher: ActorPublisher[Any] = _
  private var subscriber: Subscriber[Any] = _
  private var downstreamDemand: Long = 0L
  private var state: State = Unitialized
  private val maxPush = settings.maxInputBufferSize // FIXME why is this a good number?

  def receive = {
    case ExposedPublisher(publisher) ⇒
      exposedPublisher = publisher
      context.become(waitingForFirstSubscriber)
    case _ ⇒
      throw new IllegalStateException("The first message must be ExposedPublisher")
  }

  def waitingForFirstSubscriber: Receive = {
    case SubscribePending ⇒
      exposedPublisher.takePendingSubscribers() foreach registerSubscriber
      state = Initialized
      // hasNext might throw
      try {
        if (iterator.hasNext) context.become(active)
        else stop(Completed)
      } catch { case NonFatal(e) ⇒ stop(Errored(e)) }

  }

  def active: Receive = {
    case RequestMore(_, elements) ⇒
      downstreamDemand += elements
      if (downstreamDemand < 0) // Long has overflown, reactive-streams specification rule 3.17
        stop(Errored(new IllegalStateException(TotalPendingDemandMustNotExceedLongMaxValue)))
      else
        push()
    case PushMore ⇒
      push()
    case _: Cancel ⇒
      stop(Cancelled)
    case SubscribePending ⇒
      exposedPublisher.takePendingSubscribers() foreach registerSubscriber
  }

  // note that iterator.hasNext is always true when calling push, completing as soon as hasNext is false
  private def push(): Unit = {
    @tailrec def doPush(n: Int): Unit =
      if (downstreamDemand > 0) {
        downstreamDemand -= 1
        val hasNext = {
          tryOnNext(subscriber, iterator.next())
          iterator.hasNext
        }
        if (!hasNext)
          stop(Completed)
        else if (n == 0 && downstreamDemand > 0)
          self ! PushMore
        else
          doPush(n - 1)
      }

    try doPush(maxPush) catch {
      case NonFatal(e) ⇒ stop(Errored(e))
    }
  }

  private def registerSubscriber(sub: Subscriber[Any]): Unit = {
    subscriber match {
      case null ⇒
        subscriber = sub
        tryOnSubscribe(sub, new ActorSubscription(self, sub))
      case _ ⇒
        rejectAdditionalSubscriber(sub, exposedPublisher)
    }
  }

  private def stop(reason: StopState): Unit = {
    state match {
      case _: StopState ⇒ throw new IllegalStateException(s"Already stopped. Transition attempted from $state to $reason")
      case _ ⇒
        state = reason
        context.stop(self)
    }
  }

  override def postStop(): Unit = {
    state match {
      case Unitialized | Initialized | Cancelled ⇒
        if (exposedPublisher ne null) exposedPublisher.shutdown(ActorPublisher.NormalShutdownReason)
      case Completed ⇒
        tryOnComplete(subscriber)
        exposedPublisher.shutdown(ActorPublisher.NormalShutdownReason)
      case Errored(e) ⇒
        tryOnError(subscriber, e)
        exposedPublisher.shutdown(Some(e))
    }
    // if onComplete or onError throws we let normal supervision take care of it,
    // see reactive-streams specification rule 2:13
  }

}

