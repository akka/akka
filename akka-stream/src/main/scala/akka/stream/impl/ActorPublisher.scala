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
private[akka] class ActorSubscription[T]( final val impl: ActorRef, final val subscriber: Subscriber[_ >: T]) extends SubscriptionWithCursor[T] {
  override def request(elements: Long): Unit =
    if (elements < 1) throw new IllegalArgumentException(ReactiveStreamsConstants.NumberOfElementsInRequestMustBePositiveMsg)
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
private[akka] object IteratorPublisher {
  private[IteratorPublisher] case object Flush

  def props[T](iterator: Iterator[T], settings: MaterializerSettings): Props =
    Props(new IteratorPublisher(iterator, settings))
}

/**
 * INTERNAL API
 */
private[akka] class IteratorPublisher[T](iterator: Iterator[T], settings: MaterializerSettings)
  extends Actor
  with ActorLogging
  with SubscriberManagement[T]
  with SoftShutdown {

  import IteratorPublisher.Flush

  type S = ActorSubscription[T]
  private var demand = 0L
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
      flush()
  }

  final def active: Receive = {
    case SubscribePending ⇒
      pub.takePendingSubscribers() foreach registerSubscriber
      flush()
    case RequestMore(sub, elements) ⇒
      moreRequested(sub.asInstanceOf[S], elements)
      flush()
    case Cancel(sub) ⇒
      unregisterSubscription(sub.asInstanceOf[S])
      flush()
    case Flush ⇒
      flush()
  }

  override def postStop(): Unit =
    if (pub ne null) pub.shutdown(shutdownReason)

  private[this] def flush(): Unit = try {
    val endOfStream =
      if (iterator.hasNext) {
        if (demand > 0) {
          pushToDownstream(iterator.next())
          demand -= 1
          iterator.hasNext == false
        } else false
      } else true

    if (endOfStream) {
      completeDownstream()
      shutdownReason = None
    } else if (demand > 0) {
      self ! Flush
    }
  } catch {
    case NonFatal(e) ⇒
      abortDownstream(e)
      shutdownReason = Some(e)
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
