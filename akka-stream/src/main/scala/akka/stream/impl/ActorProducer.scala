/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import java.util.concurrent.atomic.AtomicReference

import scala.annotation.tailrec

import org.reactivestreams.api.{ Consumer, Producer }
import org.reactivestreams.spi.{ Publisher, Subscriber }

import akka.actor.ActorRef

trait ActorProducerLike[T] extends Producer[T] {
  def impl: ActorRef
  override val getPublisher: Publisher[T] = {
    val a = new ActorPublisher[T](impl)
    // Resolve cyclic dependency with actor. This MUST be the first message no matter what.
    impl ! ExposedPublisher(a.asInstanceOf[ActorPublisher[Any]])
    a
  }

  def produceTo(consumer: Consumer[T]): Unit =
    getPublisher.subscribe(consumer.getSubscriber)
}

class ActorProducer[T]( final val impl: ActorRef) extends ActorProducerLike[T]

final class ActorPublisher[T](val impl: ActorRef) extends Publisher[T] {

  // The subscriber of an subscription attempt is first placed in this list of pending subscribers.
  // The actor will call takePendingSubscribers to remove it from the list when it has received the 
  // SubscribePending message. The AtomicReference is set to null by the shutdown method, which is
  // called by the actor from postStop. Pending (unregistered) subscription attempts are denied by
  // the shutdown method. Subscription attempts after shutdown can be denied immediately.
  private val pendingSubscribers = new AtomicReference[List[Subscriber[T]]](Nil)

  override def subscribe(subscriber: Subscriber[T]): Unit = {
    @tailrec def doSubscribe(subscriber: Subscriber[T]): Unit = {
      val current = pendingSubscribers.get
      if (current eq null)
        reportShutdownError(subscriber)
      else {
        if (pendingSubscribers.compareAndSet(current, subscriber :: current))
          impl ! SubscribePending
        else
          doSubscribe(subscriber) // CAS retry
      }
    }

    doSubscribe(subscriber)
  }

  def takePendingSubscribers(): List[Subscriber[T]] =
    pendingSubscribers.getAndSet(Nil)

  def shutdown(): Unit =
    pendingSubscribers.getAndSet(null) foreach reportShutdownError

  private def reportShutdownError(subscriber: Subscriber[T]): Unit =
    subscriber.onError(new IllegalStateException("Cannot subscribe to shut-down spi.Publisher"))

}

class ActorSubscription( final val impl: ActorRef, final val _subscriber: Subscriber[Any]) extends SubscriptionWithCursor {
  override def subscriber[T]: Subscriber[T] = _subscriber.asInstanceOf[Subscriber[T]]
  override def requestMore(elements: Int): Unit =
    if (elements <= 0) throw new IllegalArgumentException("The number of requested elements must be > 0")
    else impl ! RequestMore(this, elements)
  override def cancel(): Unit = impl ! Cancel(this)
  override def toString = "ActorSubscription"
}

