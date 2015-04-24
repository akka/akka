/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.testkit

import scala.language.existentials
import akka.actor.ActorSystem
import akka.actor.DeadLetterSuppression
import akka.stream._
import akka.stream.impl._
import akka.testkit.TestProbe
import akka.stream.impl.StreamLayout.Module
import akka.stream.scaladsl._
import org.reactivestreams.{ Publisher, Subscriber, Subscription }
import scala.collection.immutable
import scala.concurrent.duration._

/**
 * Provides factory methods for various Publishers.
 */
object TestPublisher {

  import StreamTestKit._

  /**
   * Publisher that signals complete to subscribers, after handing a void subscription.
   */
  def empty[T](): Publisher[T] = EmptyPublisher[T]

  /**
   * Publisher that subscribes the subscriber and completes after the first request.
   */
  def lazyEmpty[T]: Publisher[T] = new Publisher[T] {
    override def subscribe(subscriber: Subscriber[_ >: T]): Unit =
      subscriber.onSubscribe(CompletedSubscription(subscriber))
  }

  /**
   * Publisher that signals error to subscribers immediately, before handing out subscription.
   */
  def error[T](cause: Throwable): Publisher[T] = ErrorPublisher(cause, "error").asInstanceOf[Publisher[T]]

  /**
   * Publisher that subscribes the subscriber and signals error after the first request.
   */
  def lazyError[T](cause: Throwable): Publisher[T] = new Publisher[T] {
    override def subscribe(subscriber: Subscriber[_ >: T]): Unit =
      subscriber.onSubscribe(FailedSubscription(subscriber, cause))
  }

  /**
   * Probe that implements [[org.reactivestreams.Publisher]] interface.
   */
  def manualProbe[T]()(implicit system: ActorSystem): ManualProbe[T] = new ManualProbe()

  /**
   * Probe that implements [[org.reactivestreams.Publisher]] interface and tracks demand.
   */
  def probe[T](initialPendingRequests: Long = 0)(implicit system: ActorSystem): Probe[T] = new Probe(initialPendingRequests)

  /**
   * Implementation of [[org.reactivestreams.Publisher]] that allows various assertions.
   * This probe does not track demand. Therefore you need to expect demand before sending
   * elements downstream.
   */
  class ManualProbe[I] private[TestPublisher] ()(implicit system: ActorSystem) extends Publisher[I] {

    type Self <: ManualProbe[I]

    private val probe: TestProbe = TestProbe()

    private val self = this.asInstanceOf[Self]

    /**
     * Subscribes a given [[org.reactivestreams.Subscriber]] to this probe publisher.
     */
    def subscribe(subscriber: Subscriber[_ >: I]): Unit = {
      val subscription: PublisherProbeSubscription[I] = new PublisherProbeSubscription[I](subscriber, probe)
      probe.ref ! Subscribe(subscription)
      subscriber.onSubscribe(subscription)
    }

    /**
     * Expect a subscription.
     */
    def expectSubscription(): PublisherProbeSubscription[I] =
      probe.expectMsgType[Subscribe].subscription.asInstanceOf[PublisherProbeSubscription[I]]

    /**
     * Expect demand from a given subscription.
     */
    def expectRequest(subscription: Subscription, n: Int): Self = {
      probe.expectMsg(RequestMore(subscription, n))
      self
    }

    /**
     * Expect no messages.
     */
    def expectNoMsg(): Self = {
      probe.expectNoMsg()
      self
    }

    /**
     * Expect no messages for a given duration.
     */
    def expectNoMsg(max: FiniteDuration): Self = {
      probe.expectNoMsg(max)
      self
    }

    /**
     * Receive messages for a given duration or until one does not match a given partial function.
     */
    def receiveWhile[T](max: Duration = Duration.Undefined, idle: Duration = Duration.Inf, messages: Int = Int.MaxValue)(f: PartialFunction[PublisherEvent, T]): immutable.Seq[T] =
      probe.receiveWhile(max, idle, messages)(f.asInstanceOf[PartialFunction[AnyRef, T]])

    def getPublisher: Publisher[I] = this
  }

  /**
   * Single subscription and demand tracking for [[TestPublisher.ManualProbe]].
   */
  class Probe[T] private[TestPublisher] (initialPendingRequests: Long)(implicit system: ActorSystem) extends ManualProbe[T] {

    type Self = Probe[T]

    private var pendingRequests = initialPendingRequests
    private lazy val subscription = expectSubscription()

    /**
     * Current pending requests.
     */
    def pending: Long = pendingRequests

    def sendNext(elem: T): Self = {
      if (pendingRequests == 0) pendingRequests = subscription.expectRequest()
      pendingRequests -= 1
      subscription.sendNext(elem)
      this
    }

    def unsafeSendNext(elem: T): Self = {
      subscription.sendNext(elem)
      this
    }

    def sendComplete(): Self = {
      subscription.sendComplete()
      this
    }

    def sendError(cause: Exception): Self = {
      subscription.sendError(cause)
      this
    }

    def expectRequest(): Long = subscription.expectRequest()

    def expectCancellation(): Self = {
      subscription.expectCancellation()
      this
    }
  }

}

object TestSubscriber {

  import StreamTestKit._

  /**
   * Probe that implements [[org.reactivestreams.Subscriber]] interface.
   */
  def manualProbe[T]()(implicit system: ActorSystem): ManualProbe[T] = new ManualProbe()

  def probe[T]()(implicit system: ActorSystem): Probe[T] = new Probe()

  /**
   * Implementation of [[org.reactivestreams.Subscriber]] that allows various assertions.
   */
  class ManualProbe[I] private[TestSubscriber] ()(implicit system: ActorSystem) extends Subscriber[I] {

    type Self <: ManualProbe[I]

    private val probe = TestProbe()

    private val self = this.asInstanceOf[Self]

    /**
     * Expect and return a Subscription.
     */
    def expectSubscription(): Subscription = probe.expectMsgType[OnSubscribe].subscription

    /**
     * Expect [[SubscriberEvent]].
     */
    def expectEvent(event: SubscriberEvent): Self = {
      probe.expectMsg(event)
      self
    }

    /**
     * Expect a data element.
     */
    def expectNext(element: I): Self = {
      probe.expectMsg(OnNext(element))
      self
    }

    /**
     * Expect multiple data elements.
     */
    @annotation.varargs def expectNext(e1: I, e2: I, es: I*): Self =
      expectNextN((e1 +: e2 +: es).map(identity)(collection.breakOut))

    @annotation.varargs def expectNextUnordered(e1: I, e2: I, es: I*): Self =
      expectNextUnorderedN((e1 +: e2 +: es).map(identity)(collection.breakOut))

    /**
     * Expect and return a data element.
     */
    def expectNext(): I = probe.expectMsgType[OnNext[I]].element

    def expectNextN(all: immutable.Seq[I]): Self = {
      all.foreach(e ⇒ probe.expectMsg(OnNext(e)))
      self
    }

    def expectNextUnorderedN(all: immutable.Seq[I]): Self = {
      @annotation.tailrec def expectOneOf(all: immutable.Seq[I]): Unit = all match {
        case Nil ⇒
        case list ⇒
          val next = expectNext()
          assert(all.contains(next), s"expected one of $all, but received $next")
          expectOneOf(all.diff(Seq(next)))
      }

      expectOneOf(all)
      self
    }

    /**
     * Expect completion.
     */
    def expectComplete(): Self = {
      probe.expectMsg(OnComplete)
      self
    }

    /**
     * Expect given [[Throwable]].
     */
    def expectError(cause: Throwable): Self = {
      probe.expectMsg(OnError(cause))
      self
    }

    /**
     * Expect and return a [[Throwable]].
     */
    def expectError(): Throwable = probe.expectMsgType[OnError].cause

    def expectSubscriptionAndError(cause: Throwable): Self = {
      val sub = expectSubscription()
      sub.request(1)
      expectError(cause)
      self
    }

    def expectSubscriptionAndError(): Throwable = {
      val sub = expectSubscription()
      sub.request(1)
      expectError()
    }

    def expectSubscriptionAndComplete(): Self = {
      val sub = expectSubscription()
      sub.request(1)
      expectComplete()
      self
    }

    def expectNextOrError(element: I, cause: Throwable): Either[Throwable, I] = {
      probe.fishForMessage(hint = s"OnNext($element) or ${cause.getClass.getName}") {
        case OnNext(n)        ⇒ true
        case OnError(`cause`) ⇒ true
      } match {
        case OnNext(n: I @unchecked) ⇒ Right(n)
        case OnError(err)            ⇒ Left(err)
      }
    }

    def expectNextOrComplete(element: I): Self = {
      probe.fishForMessage(hint = s"OnNext($element) or OnComplete") {
        case OnNext(n)  ⇒ true
        case OnComplete ⇒ true
      }
      self
    }

    def expectNoMsg(): Self = {
      probe.expectNoMsg()
      self
    }

    def expectNoMsg(max: FiniteDuration): Self = {
      probe.expectNoMsg(max)
      self
    }

    /**
     * Receive messages for a given duration or until one does not match a given partial function.
     */
    def receiveWhile[T](max: Duration = Duration.Undefined, idle: Duration = Duration.Inf, messages: Int = Int.MaxValue)(f: PartialFunction[SubscriberEvent, T]): immutable.Seq[T] =
      probe.receiveWhile(max, idle, messages)(f.asInstanceOf[PartialFunction[AnyRef, T]])

    def within[T](max: FiniteDuration)(f: ⇒ T): T = probe.within(0.seconds, max)(f)

    def onSubscribe(subscription: Subscription): Unit = probe.ref ! OnSubscribe(subscription)
    def onNext(element: I): Unit = probe.ref ! OnNext(element)
    def onComplete(): Unit = probe.ref ! OnComplete
    def onError(cause: Throwable): Unit = probe.ref ! OnError(cause)
  }

  /**
   * Single subscription tracking for [[ManualProbe]].
   */
  class Probe[T] private[TestSubscriber] ()(implicit system: ActorSystem) extends ManualProbe[T] {

    override type Self = Probe[T]

    private lazy val subscription = expectSubscription()

    def request(n: Long): Self = {
      subscription.request(n)
      this
    }

    def requestNext(element: T): Self = {
      subscription.request(1)
      expectNext(element)
      this
    }

    def cancel(): Self = {
      subscription.cancel()
      this
    }
  }
}

/**
 * INTERNAL API
 */
private[testkit] object StreamTestKit {

  import TestPublisher._
  import TestSubscriber._

  sealed trait PublisherEvent extends DeadLetterSuppression
  final case class Subscribe(subscription: Subscription) extends PublisherEvent
  final case class CancelSubscription(subscription: Subscription) extends PublisherEvent
  final case class RequestMore(subscription: Subscription, elements: Long) extends PublisherEvent

  sealed trait SubscriberEvent extends DeadLetterSuppression
  final case class OnSubscribe(subscription: Subscription) extends SubscriberEvent
  final case class OnNext[I](element: I) extends SubscriberEvent
  final case object OnComplete extends SubscriberEvent
  final case class OnError(cause: Throwable) extends SubscriberEvent

  final case class CompletedSubscription[T](subscriber: Subscriber[T]) extends Subscription {
    override def request(elements: Long): Unit = subscriber.onComplete()
    override def cancel(): Unit = ()
  }

  final case class FailedSubscription[T](subscriber: Subscriber[T], cause: Throwable) extends Subscription {
    override def request(elements: Long): Unit = subscriber.onError(cause)
    override def cancel(): Unit = ()
  }

  final case class PublisherProbeSubscription[I](subscriber: Subscriber[_ >: I], publisherProbe: TestProbe) extends Subscription {
    def request(elements: Long): Unit = publisherProbe.ref ! RequestMore(this, elements)
    def cancel(): Unit = publisherProbe.ref ! CancelSubscription(this)

    def expectRequest(n: Long): Unit = publisherProbe.expectMsg(RequestMore(this, n))
    def expectRequest(): Long = publisherProbe.expectMsgPF() {
      case RequestMore(sub, n) if sub eq this ⇒ n
    }

    def expectCancellation(): Unit = publisherProbe.fishForMessage() {
      case CancelSubscription(sub) if sub eq this ⇒ true
      case RequestMore(sub, _) if sub eq this     ⇒ false
    }

    def sendNext(element: I): Unit = subscriber.onNext(element)
    def sendComplete(): Unit = subscriber.onComplete()
    def sendError(cause: Exception): Unit = subscriber.onError(cause)
  }

  final class ProbeSource[T](val attributes: OperationAttributes, shape: SourceShape[T])(implicit system: ActorSystem) extends SourceModule[T, TestPublisher.Probe[T]](shape) {
    override def create(context: MaterializationContext) = {
      val probe = TestPublisher.probe[T]()
      (probe, probe)
    }
    override protected def newInstance(shape: SourceShape[T]): SourceModule[T, TestPublisher.Probe[T]] = new ProbeSource[T](attributes, shape)
    override def withAttributes(attr: OperationAttributes): Module = new ProbeSource[T](attr, amendShape(attr))
  }

  final class ProbeSink[T](val attributes: OperationAttributes, shape: SinkShape[T])(implicit system: ActorSystem) extends SinkModule[T, TestSubscriber.Probe[T]](shape) {
    override def create(context: MaterializationContext) = {
      val probe = TestSubscriber.probe[T]()
      (probe, probe)
    }
    override protected def newInstance(shape: SinkShape[T]): SinkModule[T, TestSubscriber.Probe[T]] = new ProbeSink[T](attributes, shape)
    override def withAttributes(attr: OperationAttributes): Module = new ProbeSink[T](attr, amendShape(attr))
  }

}
