/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import java.util.concurrent.atomic.AtomicReference

import akka.actor.{ Actor, ActorLogging, ActorRef, Props, Terminated }
import akka.stream.{ MaterializerSettings, Stop }
import org.reactivestreams.{ Publisher, Subscriber }

import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.duration.Duration
import scala.util.control.{ NoStackTrace, NonFatal }

/**
 * INTERNAL API
 */
private[akka] object SimpleCallbackPublisher {
  def props[T](settings: MaterializerSettings, f: () ⇒ T): Props =
    Props(new SimpleCallbackPublisher(f, settings)).withDispatcher(settings.dispatcher)

  case object Generate

}

/**
 * INTERNAL API
 */
private[akka] trait SoftShutdown { this: Actor ⇒

  var substreamCount: Int = 0

  def softShutdown(): Unit = {
    if (substreamCount == 0) {
      context.stop(self)
    }
  }
}

/**
 * INTERNAL API
 */
private[akka] class SimpleCallbackPublisher[T](f: () ⇒ T, settings: MaterializerSettings)
  extends Actor
  with ActorLogging
  with SubscriberManagement[T]
  with SoftShutdown {

  import akka.stream.impl.SimpleCallbackPublisher._

  type S = LazySubscription[T]
  var pub: LazyPublisherLike[T] = _
  var shutdownReason: Option[Throwable] = LazyActorPublisher.NormalShutdownReason

  context.setReceiveTimeout(settings.downstreamSubscriptionTimeout)

  final def receive = {
    case ExposedPublisher(publisher) ⇒
      this.pub = publisher.asInstanceOf[LazyPublisherLike[T]]
      val earlySubscriptions = pub.takeEarlySubscribers(self)
      earlySubscriptions foreach {
        case (lazySubscription, _) ⇒
          registerSubscriber(lazySubscription.subscriber.asInstanceOf[Subscriber[T]], lazySubscription.asInstanceOf[S])
      }
      earlySubscriptions foreach {
        case (lazySubscription, demand) ⇒ moreRequested(lazySubscription.asInstanceOf[S], demand)
      }

      context.become(active)
      generate()
  }

  final def active: Receive = {
    case SubscribePending ⇒
      pub.takePendingSubscribers() foreach registerSubscriber
    case RequestMore(sub, elements) ⇒
      moreRequested(sub.asInstanceOf[S], elements)
      generate()
    case Cancel(sub) ⇒
      unregisterSubscription(sub.asInstanceOf[S])
      generate()
    case Generate ⇒
      generate()
  }

  override def postStop(): Unit =
    if (pub ne null) pub.shutdown(shutdownReason)

  private var demand = 0
  private def generate(): Unit = {
    if (demand > 0) {
      try {
        demand -= 1
        pushToDownstream(f())
        if (demand > 0) self ! Generate
      } catch {
        case Stop        ⇒ { completeDownstream(); shutdownReason = None }
        case NonFatal(e) ⇒ { abortDownstream(e); shutdownReason = Some(e) }
      }
    }
  }

  override def initialBufferSize = settings.initialFanOutBufferSize
  override def maxBufferSize = settings.maxFanOutBufferSize

  override def createSubscription(subscriber: Subscriber[T]): LazySubscription[T] =
    new LazySubscription(pub, subscriber)

  override def requestFromUpstream(elements: Int): Unit = demand += elements

  override def cancelUpstream(): Unit = {
    pub.shutdown(shutdownReason)
    softShutdown()
  }
  override def shutdown(completed: Boolean): Unit = {
    pub.shutdown(shutdownReason)
    softShutdown()
  }

}
