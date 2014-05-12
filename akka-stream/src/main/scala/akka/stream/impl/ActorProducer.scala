/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.collection.immutable
import org.reactivestreams.api.{ Consumer, Producer }
import org.reactivestreams.spi.{ Publisher, Subscriber }
import akka.actor.ActorRef
import akka.stream.MaterializerSettings
import akka.actor.ActorLogging
import akka.actor.Actor
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal
import akka.actor.Props
import scala.util.control.NoStackTrace
import akka.stream.Stop
import akka.actor.Terminated

/**
 * INTERNAL API
 */
private[akka] trait ActorProducerLike[T] extends Producer[T] {
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

/**
 * INTERNAL API
 * If equalityValue is defined it is used for equals and hashCode, otherwise default reference equality.
 */
private[akka] class ActorProducer[T]( final val impl: ActorRef, val equalityValue: Option[AnyRef] = None) extends ActorProducerLike[T] {
  override def equals(o: Any): Boolean = (equalityValue, o) match {
    case (Some(v), ActorProducer(_, Some(otherValue))) ⇒ v.equals(otherValue)
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
private[akka] object ActorProducer {
  def props[T](settings: MaterializerSettings, f: () ⇒ T): Props =
    Props(new ActorProducerImpl(f, settings))

  def unapply(o: Any): Option[(ActorRef, Option[AnyRef])] = o match {
    case other: ActorProducer[_] ⇒ Some((other.impl, other.equalityValue))
    case _                       ⇒ None
  }
}

/**
 * INTERNAL API
 */
private[akka] object ActorPublisher {
  class NormalShutdownException extends IllegalStateException("Cannot subscribe to shut-down spi.Publisher") with NoStackTrace
  val NormalShutdownReason: Option[Throwable] = Some(new NormalShutdownException)
}

/**
 * INTERNAL API
 */
private[akka] final class ActorPublisher[T](val impl: ActorRef) extends Publisher[T] {

  // The subscriber of an subscription attempt is first placed in this list of pending subscribers.
  // The actor will call takePendingSubscribers to remove it from the list when it has received the 
  // SubscribePending message. The AtomicReference is set to null by the shutdown method, which is
  // called by the actor from postStop. Pending (unregistered) subscription attempts are denied by
  // the shutdown method. Subscription attempts after shutdown can be denied immediately.
  private val pendingSubscribers = new AtomicReference[immutable.Seq[Subscriber[T]]](Nil)

  override def subscribe(subscriber: Subscriber[T]): Unit = {
    @tailrec def doSubscribe(subscriber: Subscriber[T]): Unit = {
      val current = pendingSubscribers.get
      if (current eq null)
        reportSubscribeError(subscriber)
      else {
        if (pendingSubscribers.compareAndSet(current, subscriber +: current))
          impl ! SubscribePending
        else
          doSubscribe(subscriber) // CAS retry
      }
    }

    doSubscribe(subscriber)
  }

  def takePendingSubscribers(): immutable.Seq[Subscriber[T]] = {
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

  private def reportSubscribeError(subscriber: Subscriber[T]): Unit =
    shutdownReason match {
      case Some(e) ⇒ subscriber.onError(e)
      case None    ⇒ subscriber.onComplete()
    }
}

/**
 * INTERNAL API
 */
private[akka] class ActorSubscription[T]( final val impl: ActorRef, final val subscriber: Subscriber[T]) extends SubscriptionWithCursor[T] {
  override def requestMore(elements: Int): Unit =
    if (elements <= 0) throw new IllegalArgumentException("The number of requested elements must be > 0")
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
private[akka] object ActorProducerImpl {
  case object Generate
}

/**
 * INTERNAL API
 */
private[akka] class ActorProducerImpl[T](f: () ⇒ T, settings: MaterializerSettings)
  extends Actor
  with ActorLogging
  with SubscriberManagement[T]
  with SoftShutdown {

  import ActorProducerImpl._
  import ActorBasedFlowMaterializer._

  type S = ActorSubscription[T]
  var pub: ActorPublisher[T] = _
  var shutdownReason: Option[Throwable] = ActorPublisher.NormalShutdownReason

  context.setReceiveTimeout(settings.downstreamSubscriptionTimeout)

  final def receive = {
    case ExposedPublisher(pub) ⇒
      this.pub = pub.asInstanceOf[ActorPublisher[T]]
      context.become(waitingForSubscribers)
  }

  final def waitingForSubscribers: Receive = {
    case SubscribePending ⇒
      pub.takePendingSubscribers() foreach registerSubscriber
      context.setReceiveTimeout(Duration.Undefined)
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

  override def postStop(): Unit = {
    pub.shutdown(shutdownReason)
  }

  private var demand = 0
  private def generate(): Unit = {
    if (demand > 0) {
      try {
        demand -= 1
        pushToDownstream(withCtx(context)(f()))
        if (demand > 0) self ! Generate
      } catch {
        case Stop        ⇒ { completeDownstream(); shutdownReason = None }
        case NonFatal(e) ⇒ { abortDownstream(e); shutdownReason = Some(e) }
      }
    }
  }

  override def initialBufferSize = settings.initialFanOutBufferSize
  override def maxBufferSize = settings.maxFanOutBufferSize

  override def createSubscription(subscriber: Subscriber[T]): ActorSubscription[T] =
    new ActorSubscription(self, subscriber)

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
