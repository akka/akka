/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import java.util.concurrent.atomic.AtomicReference

import akka.actor.{ Actor, ActorLogging, ActorRef, Props, Terminated }
import akka.stream.{ ReactiveStreamsConstants, MaterializerSettings }
import org.reactivestreams.{ Publisher, Subscriber }

import scala.annotation.tailrec
import scala.collection.immutable
import scala.util.control.{ NoStackTrace, NonFatal }

/**
 * INTERNAL API
 */
private[akka] object SimpleCallbackPublisher {
  def props[T](settings: MaterializerSettings, f: () ⇒ T): Props =
    Props(new SimpleCallbackPublisherImpl(f, settings)).withDispatcher(settings.dispatcher)

}

/**
 * INTERNAL API
 */
private[akka] object ActorPublisher {
  class NormalShutdownException extends IllegalStateException("Cannot subscribe to shut-down spi.Publisher") with NoStackTrace
  val NormalShutdownReason: Option[Throwable] = Some(new NormalShutdownException)

  def apply[T](impl: ActorRef, equalityValue: Option[AnyRef] = None): ActorPublisher[T] = {
    val a = new ActorPublisher[T](impl, equalityValue)
    // Resolve cyclic dependency with actor. This MUST be the first message no matter what.
    impl ! ExposedPublisher(a.asInstanceOf[ActorPublisher[Any]])
    a
  }

  def unapply(o: Any): Option[(ActorRef, Option[AnyRef])] = o match {
    case other: ActorPublisher[_] ⇒ Some((other.impl, other.equalityValue))
    case _                        ⇒ None
  }
}

/**
 * INTERNAL API
 *
 * When you instantiate this class, or its subclasses, you MUST send an ExposedPublisher message to the wrapped
 * ActorRef! If you don't need to subclass, prefer the apply() method on the companion object which takes care of this.
 */
private[akka] class ActorPublisher[T](val impl: ActorRef, val equalityValue: Option[AnyRef]) extends Publisher[T] {

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

  override def equals(o: Any): Boolean = (equalityValue, o) match {
    case (Some(v), ActorPublisher(_, Some(otherValue))) ⇒ v.equals(otherValue)
    case _ ⇒ super.equals(o)
  }

  override def hashCode: Int = equalityValue match {
    case Some(v) ⇒ v.hashCode
    case None    ⇒ super.hashCode
  }
}

/**
 * INTERNAL API
 */
private[akka] class ActorSubscription[T]( final val impl: ActorRef, final val subscriber: Subscriber[_ >: T]) extends SubscriptionWithCursor[T] {
  override def request(elements: Long): Unit =
    if (elements <= 0) throw new IllegalArgumentException(ReactiveStreamsConstants.NumberOfElementsInRequestMustBePositiveMsg)
    else impl ! RequestMore(this, elements)
  override def cancel(): Unit = impl ! Cancel(this)
}

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

/**
 * INTERNAL API
 */
private[akka] object SimpleCallbackPublisherImpl {
  case object Generate
}

/**
 * INTERNAL API
 */
private[akka] class SimpleCallbackPublisherImpl[T](f: () ⇒ T, settings: MaterializerSettings)
  extends Actor
  with ActorLogging
  with SubscriberManagement[T]
  with SoftShutdown {

  import akka.stream.impl.SimpleCallbackPublisherImpl._

  type S = ActorSubscription[T]
  var pub: ActorPublisher[T] = _
  var shutdownReason: Option[Throwable] = ActorPublisher.NormalShutdownReason

  final def receive = {
    case ExposedPublisher(pub) ⇒
      this.pub = pub.asInstanceOf[ActorPublisher[T]]
      context.become(waitingForSubscribers)
  }

  final def waitingForSubscribers: Receive = {
    case SubscribePending ⇒
      pub.takePendingSubscribers() foreach registerSubscriber
      context.become(active)
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

  private var demand = 0L
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

  override def createSubscription(subscriber: Subscriber[_ >: T]): ActorSubscription[T] =
    new ActorSubscription(self, subscriber)

  override def requestFromUpstream(elements: Long): Unit = demand += elements

  override def cancelUpstream(): Unit = {
    pub.shutdown(shutdownReason)
    softShutdown()
  }
  override def shutdown(completed: Boolean): Unit = {
    pub.shutdown(shutdownReason)
    softShutdown()
  }

}
