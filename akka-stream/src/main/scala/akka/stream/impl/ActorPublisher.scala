/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.collection.immutable
import scala.util.control.{ NoStackTrace, NonFatal }
import akka.actor.{ Actor, ActorLogging, ActorRef, Props, Terminated }
import akka.stream.{ ReactiveStreamsConstants, MaterializerSettings }
import org.reactivestreams.{ Publisher, Subscriber }
import org.reactivestreams.Subscription

/**
 * INTERNAL API
 */
private[akka] object ActorPublisher {
  class NormalShutdownException extends IllegalStateException("Cannot subscribe to shut-down spi.Publisher") with NoStackTrace
  val NormalShutdownReason: Option[Throwable] = Some(new NormalShutdownException)

  def apply[T](impl: ActorRef): ActorPublisher[T] = {
    val a = new ActorPublisher[T](impl)
    // Resolve cyclic dependency with actor. This MUST be the first message no matter what.
    impl ! ExposedPublisher(a.asInstanceOf[ActorPublisher[Any]])
    a
  }

}

/**
 * INTERNAL API
 *
 * When you instantiate this class, or its subclasses, you MUST send an ExposedPublisher message to the wrapped
 * ActorRef! If you don't need to subclass, prefer the apply() method on the companion object which takes care of this.
 */
private[akka] class ActorPublisher[T](val impl: ActorRef) extends Publisher[T] {

  // The subscriber of an subscription attempt is first placed in this list of pending subscribers.
  // The actor will call takePendingSubscribers to remove it from the list when it has received the
  // SubscribePending message. The AtomicReference is set to null by the shutdown method, which is
  // called by the actor from postStop. Pending (unregistered) subscription attempts are denied by
  // the shutdown method. Subscription attempts after shutdown can be denied immediately.
  private val pendingSubscribers = new AtomicReference[immutable.Seq[Subscriber[_ >: T]]](Nil)

  protected val wakeUpMsg: Any = SubscribePending

  override def subscribe(subscriber: Subscriber[_ >: T]): Unit = {
    @tailrec def doSubscribe(subscriber: Subscriber[_ >: T]): Unit = {
      val current = pendingSubscribers.get
      if (current eq null)
        reportSubscribeError(subscriber)
      else {
        if (pendingSubscribers.compareAndSet(current, subscriber +: current))
          impl ! wakeUpMsg
        else
          doSubscribe(subscriber) // CAS retry
      }
    }

    doSubscribe(subscriber)
  }

  def takePendingSubscribers(): immutable.Seq[Subscriber[_ >: T]] = {
    val pending = pendingSubscribers.getAndSet(Nil)
    assert(pending ne null, "takePendingSubscribers must not be called after shutdown")
    pending.reverse
  }

  def shutdown(reason: Option[Throwable]): Unit = {
    shutdownReason = reason
    pendingSubscribers.getAndSet(null) match {
      case null    ⇒ // already called earlier
      case pending ⇒ pending foreach reportSubscribeError
    }
  }

  @volatile private var shutdownReason: Option[Throwable] = None

  private def reportSubscribeError(subscriber: Subscriber[_ >: T]): Unit =
    shutdownReason match {
      case Some(e) ⇒ subscriber.onError(e)
      case None    ⇒ subscriber.onComplete()
    }

}

/**
 * INTERNAL API
 */
private[akka] class ActorSubscription[T]( final val impl: ActorRef, final val subscriber: Subscriber[_ >: T]) extends Subscription {
  override def request(elements: Long): Unit =
    if (elements < 1) throw new IllegalArgumentException(ReactiveStreamsConstants.NumberOfElementsInRequestMustBePositiveMsg)
    else impl ! RequestMore(this, elements)
  override def cancel(): Unit = impl ! Cancel(this)
}

/**
 * INTERNAL API
 */
private[akka] class ActorSubscriptionWithCursor[T](_impl: ActorRef, _subscriber: Subscriber[_ >: T])
  extends ActorSubscription[T](_impl, _subscriber) with SubscriptionWithCursor[T]

/**
 * INTERNAL API
 */
private[akka] trait SoftShutdown { this: Actor ⇒
  def softShutdown(): Unit = {
    val children = context.children
    if (children.isEmpty) {
      context.stop(self)
    } else {
      context.children foreach context.watch
      context.become {
        case Terminated(_) ⇒ if (context.children.isEmpty) context.stop(self)
        case _             ⇒ // ignore all the rest, we’re practically dead
      }
    }
  }
}

