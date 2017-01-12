/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.testkit

import akka.actor.{ ActorSystem, DeadLetterSuppression, NoSerializationVerificationNeeded }
import akka.stream._
import akka.stream.impl.StreamLayout.Module
import akka.stream.impl._
import akka.testkit.TestProbe
import org.reactivestreams.{ Publisher, Subscriber, Subscription }
import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.duration._
import scala.language.existentials
import java.io.StringWriter
import java.io.PrintWriter

/**
 * Provides factory methods for various Publishers.
 */
object TestPublisher {

  import StreamTestKit._

  trait PublisherEvent extends DeadLetterSuppression with NoSerializationVerificationNeeded
  final case class Subscribe(subscription: Subscription) extends PublisherEvent
  final case class CancelSubscription(subscription: Subscription) extends PublisherEvent
  final case class RequestMore(subscription: Subscription, elements: Long) extends PublisherEvent

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
   * Publisher that signals error to subscribers immediately after handing out subscription.
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
  def manualProbe[T](autoOnSubscribe: Boolean = true)(implicit system: ActorSystem): ManualProbe[T] = new ManualProbe(autoOnSubscribe)

  /**
   * Probe that implements [[org.reactivestreams.Publisher]] interface and tracks demand.
   */
  def probe[T](initialPendingRequests: Long = 0)(implicit system: ActorSystem): Probe[T] = new Probe(initialPendingRequests)

  /**
   * Implementation of [[org.reactivestreams.Publisher]] that allows various assertions.
   * This probe does not track demand. Therefore you need to expect demand before sending
   * elements downstream.
   */
  class ManualProbe[I] private[TestPublisher] (autoOnSubscribe: Boolean = true)(implicit system: ActorSystem) extends Publisher[I] {

    type Self <: ManualProbe[I]

    private val probe: TestProbe = TestProbe()

    private val self = this.asInstanceOf[Self]

    /**
     * Subscribes a given [[org.reactivestreams.Subscriber]] to this probe publisher.
     */
    def subscribe(subscriber: Subscriber[_ >: I]): Unit = {
      val subscription: PublisherProbeSubscription[I] = new PublisherProbeSubscription[I](subscriber, probe)
      probe.ref ! Subscribe(subscription)
      if (autoOnSubscribe) subscriber.onSubscribe(subscription)
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

    def expectEventPF[T](f: PartialFunction[PublisherEvent, T]): T =
      probe.expectMsgPF[T]()(f.asInstanceOf[PartialFunction[Any, T]])

    def getPublisher: Publisher[I] = this

    /**
     * Execute code block while bounding its execution time between `min` and
     * `max`. `within` blocks may be nested. All methods in this trait which
     * take maximum wait times are available in a version which implicitly uses
     * the remaining time governed by the innermost enclosing `within` block.
     *
     * Note that the timeout is scaled using Duration.dilated, which uses the
     * configuration entry "akka.test.timefactor", while the min Duration is not.
     *
     * {{{
     * val ret = within(50 millis) {
     *   test ! "ping"
     *   expectMsgClass(classOf[String])
     * }
     * }}}
     */
    def within[T](min: FiniteDuration, max: FiniteDuration)(f: ⇒ T): T = probe.within(min, max)(f)

    /**
     * Same as calling `within(0 seconds, max)(f)`.
     */
    def within[T](max: FiniteDuration)(f: ⇒ T): T = probe.within(max)(f)
  }

  /**
   * Single subscription and demand tracking for [[TestPublisher.ManualProbe]].
   */
  class Probe[T] private[TestPublisher] (initialPendingRequests: Long)(implicit system: ActorSystem) extends ManualProbe[T] {

    type Self = Probe[T]

    private var pendingRequests = initialPendingRequests
    private lazy val subscription = expectSubscription()

    /** Asserts that a subscription has been received or will be received */
    def ensureSubscription(): Unit = subscription // initializes lazy val

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

    def expectRequest(): Long = {
      val requests = subscription.expectRequest()
      pendingRequests += requests
      requests
    }

    def expectCancellation(): Self = {
      subscription.expectCancellation()
      this
    }
  }

}

object TestSubscriber {

  trait SubscriberEvent extends DeadLetterSuppression with NoSerializationVerificationNeeded
  final case class OnSubscribe(subscription: Subscription) extends SubscriberEvent
  final case class OnNext[I](element: I) extends SubscriberEvent
  case object OnComplete extends SubscriberEvent
  final case class OnError(cause: Throwable) extends SubscriberEvent {
    override def toString: String = {
      val str = new StringWriter
      val out = new PrintWriter(str)
      out.print("OnError(")
      cause.printStackTrace(out)
      out.print(")")
      str.toString
    }
  }

  /**
   * Probe that implements [[org.reactivestreams.Subscriber]] interface.
   */
  def manualProbe[T]()(implicit system: ActorSystem): ManualProbe[T] = new ManualProbe()

  def probe[T]()(implicit system: ActorSystem): Probe[T] = new Probe()

  /**
   * Implementation of [[org.reactivestreams.Subscriber]] that allows various assertions.
   *
   * All timeouts are dilated automatically, for more details about time dilation refer to [[akka.testkit.TestKit]].
   */
  class ManualProbe[I] private[TestSubscriber] ()(implicit system: ActorSystem) extends Subscriber[I] {
    import akka.testkit._

    type Self <: ManualProbe[I]

    private val probe = TestProbe()

    @volatile private var _subscription: Subscription = _

    private val self = this.asInstanceOf[Self]

    /**
     * Expect and return a [[org.reactivestreams.Subscription]].
     */
    def expectSubscription(): Subscription = {
      _subscription = probe.expectMsgType[OnSubscribe].subscription
      _subscription
    }

    /**
     * Expect and return [[SubscriberEvent]] (any of: `OnSubscribe`, `OnNext`, `OnError` or `OnComplete`).
     */
    def expectEvent(): SubscriberEvent =
      probe.expectMsgType[SubscriberEvent]

    /**
     * Expect and return [[SubscriberEvent]] (any of: `OnSubscribe`, `OnNext`, `OnError` or `OnComplete`).
     */
    def expectEvent(max: FiniteDuration): SubscriberEvent =
      probe.expectMsgType[SubscriberEvent](max)

    /**
     * Fluent DSL
     *
     * Expect [[SubscriberEvent]] (any of: `OnSubscribe`, `OnNext`, `OnError` or `OnComplete`).
     */
    def expectEvent(event: SubscriberEvent): Self = {
      probe.expectMsg(event)
      self
    }

    /**
     * Expect and return a stream element.
     */
    def expectNext(): I = {
      expectNext(probe.testKitSettings.SingleExpectDefaultTimeout.dilated)
    }

    /**
     * Expect and return a stream element during specified time or timeout.
     */
    def expectNext(d: FiniteDuration): I = {
      val t = probe.remainingOr(d)
      probe.receiveOne(t) match {
        case null         ⇒ throw new AssertionError(s"Expected OnNext(_), yet no element signaled during $t")
        case OnNext(elem) ⇒ elem.asInstanceOf[I]
        case other        ⇒ throw new AssertionError("expected OnNext, found " + other)
      }
    }

    /**
     * Fluent DSL
     *
     * Expect a stream element.
     */
    def expectNext(element: I): Self = {
      probe.expectMsg(OnNext(element))
      self
    }

    /**
     * Fluent DSL
     *
     * Expect a stream element during specified time or timeout.
     */
    def expectNext(d: FiniteDuration, element: I): Self = {
      probe.expectMsg(d, OnNext(element))
      self
    }

    /**
     * Fluent DSL
     *
     * Expect multiple stream elements.
     */
    @annotation.varargs def expectNext(e1: I, e2: I, es: I*): Self =
      expectNextN((e1 +: e2 +: es).map(identity)(collection.breakOut))

    /**
     * Fluent DSL
     *
     * Expect multiple stream elements in arbitrary order.
     */
    @annotation.varargs def expectNextUnordered(e1: I, e2: I, es: I*): Self =
      expectNextUnorderedN((e1 +: e2 +: es).map(identity)(collection.breakOut))

    /**
     * Expect and return the next `n` stream elements.
     */
    def expectNextN(n: Long): immutable.Seq[I] = {
      val b = immutable.Seq.newBuilder[I]
      var i = 0
      while (i < n) {
        val next = probe.expectMsgType[OnNext[I]]
        b += next.element
        i += 1
      }

      b.result()
    }

    /**
     * Fluent DSL
     * Expect the given elements to be signalled in order.
     */
    def expectNextN(all: immutable.Seq[I]): Self = {
      all.foreach(e ⇒ probe.expectMsg(OnNext(e)))
      self
    }

    /**
     * Fluent DSL
     * Expect the given elements to be signalled in any order.
     */
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
     * Fluent DSL
     *
     * Expect completion.
     */
    def expectComplete(): Self = {
      probe.expectMsg(OnComplete)
      self
    }

    /**
     * Expect and return the signalled [[Throwable]].
     */
    def expectError(): Throwable = probe.expectMsgType[OnError].cause

    /**
     * Fluent DSL
     *
     * Expect given [[Throwable]].
     */
    def expectError(cause: Throwable): Self = {
      probe.expectMsg(OnError(cause))
      self
    }

    /**
     * Expect subscription to be followed immediatly by an error signal.
     *
     * By default `1` demand will be signalled in order to wake up a possibly lazy upstream.
     *
     * See also [[#expectSubscriptionAndError(Boolean)]] if no demand should be signalled.
     */
    def expectSubscriptionAndError(): Throwable = {
      expectSubscriptionAndError(true)
    }

    /**
     * Expect subscription to be followed immediatly by an error signal.
     *
     * Depending on the `signalDemand` parameter demand may be signalled immediatly after obtaining the subscription
     * in order to wake up a possibly lazy upstream. You can disable this by setting the `signalDemand` parameter to `false`.
     *
     * See also [[#expectSubscriptionAndError()]].
     */
    def expectSubscriptionAndError(signalDemand: Boolean): Throwable = {
      val sub = expectSubscription()
      if (signalDemand) sub.request(1)
      expectError()
    }

    /**
     * Fluent DSL
     *
     * Expect subscription followed by immediate stream completion.
     *
     * By default `1` demand will be signalled in order to wake up a possibly lazy upstream.
     *
     * See also [[#expectSubscriptionAndComplete(cause: Throwable, signalDemand: Boolean)]] if no demand should be signalled.
     */
    def expectSubscriptionAndError(cause: Throwable): Self =
      expectSubscriptionAndError(cause, signalDemand = true)

    /**
     * Fluent DSL
     *
     * Expect subscription followed by immediate stream completion.
     * By default `1` demand will be signalled in order to wake up a possibly lazy upstream
     *
     * See also [[#expectSubscriptionAndError(cause: Throwable)]].
     */
    def expectSubscriptionAndError(cause: Throwable, signalDemand: Boolean): Self = {
      val sub = expectSubscription()
      if (signalDemand) sub.request(1)
      expectError(cause)
      self
    }

    /**
     * Fluent DSL
     *
     * Expect subscription followed by immediate stream completion.
     * By default `1` demand will be signalled in order to wake up a possibly lazy upstream
     *
     * See also [[#expectSubscriptionAndComplete(signalDemand: Boolean)]] if no demand should be signalled.
     */
    def expectSubscriptionAndComplete(): Self =
      expectSubscriptionAndComplete(true)

    /**
     * Fluent DSL
     *
     * Expect subscription followed by immediate stream completion.
     *
     * Depending on the `signalDemand` parameter demand may be signalled immediatly after obtaining the subscription
     * in order to wake up a possibly lazy upstream. You can disable this by setting the `signalDemand` parameter to `false`.
     *
     * See also [[#expectSubscriptionAndComplete]].
     */
    def expectSubscriptionAndComplete(signalDemand: Boolean): Self = {
      val sub = expectSubscription()
      if (signalDemand) sub.request(1)
      expectComplete()
      self
    }

    /**
     * Fluent DSL
     *
     * Expect given next element or error signal, returning whichever was signalled.
     */
    def expectNextOrError(): Either[Throwable, I] = {
      probe.fishForMessage(hint = s"OnNext(_) or error") {
        case OnNext(element) ⇒ true
        case OnError(cause)  ⇒ true
      } match {
        case OnNext(n: I @unchecked) ⇒ Right(n)
        case OnError(err)            ⇒ Left(err)
      }
    }

    /**
     * Fluent DSL
     * Expect given next element or error signal.
     */
    def expectNextOrError(element: I, cause: Throwable): Either[Throwable, I] = {
      probe.fishForMessage(hint = s"OnNext($element) or ${cause.getClass.getName}") {
        case OnNext(`element`) ⇒ true
        case OnError(`cause`)  ⇒ true
      } match {
        case OnNext(n: I @unchecked) ⇒ Right(n)
        case OnError(err)            ⇒ Left(err)
      }
    }

    /**
     * Expect next element or stream completion - returning whichever was signalled.
     */
    def expectNextOrComplete(): Either[OnComplete.type, I] = {
      probe.fishForMessage(hint = s"OnNext(_) or OnComplete") {
        case OnNext(n)  ⇒ true
        case OnComplete ⇒ true
      } match {
        case OnComplete              ⇒ Left(OnComplete)
        case OnNext(n: I @unchecked) ⇒ Right(n)
      }
    }

    /**
     * Fluent DSL
     *
     * Expect given next element or stream completion.
     */
    def expectNextOrComplete(element: I): Self = {
      probe.fishForMessage(hint = s"OnNext($element) or OnComplete") {
        case OnNext(`element`) ⇒ true
        case OnComplete        ⇒ true
      }
      self
    }

    /**
     * Fluent DSL
     *
     * Same as `expectNoMsg(remaining)`, but correctly treating the timeFactor.
     */
    def expectNoMsg(): Self = {
      probe.expectNoMsg()
      self
    }

    /**
     * Fluent DSL
     *
     * Assert that no message is received for the specified time.
     */
    def expectNoMsg(remaining: FiniteDuration): Self = {
      probe.expectNoMsg(remaining)
      self
    }

    def expectNextPF[T](f: PartialFunction[Any, T]): T = {
      expectEventPF {
        case OnNext(n) if f.isDefinedAt(n) ⇒ f(n)
      }
    }

    /**
     * Expect next element and test it with partial function.
     *
     * Allows chaining probe methods.
     */
    def expectNextChainingPF(f: PartialFunction[Any, Any]): Self = {
      expectNextPF(f.andThen(_ ⇒ self))
    }

    def expectEventPF[T](f: PartialFunction[SubscriberEvent, T]): T =
      probe.expectMsgPF[T](hint = "message matching partial function")(f.asInstanceOf[PartialFunction[Any, T]])

    /**
     * Receive messages for a given duration or until one does not match a given partial function.
     */
    def receiveWhile[T](max: Duration = Duration.Undefined, idle: Duration = Duration.Inf, messages: Int = Int.MaxValue)(f: PartialFunction[SubscriberEvent, T]): immutable.Seq[T] =
      probe.receiveWhile(max, idle, messages)(f.asInstanceOf[PartialFunction[AnyRef, T]])

    /**
     * Drains a given number of messages
     */
    def receiveWithin(max: FiniteDuration, messages: Int = Int.MaxValue): immutable.Seq[I] =
      probe.receiveWhile(max, max, messages) {
        case OnNext(i) ⇒ Some(i.asInstanceOf[I])
        case _         ⇒ None
      }.flatten

    /**
     * Attempt to drain the stream into a strict collection (by requesting `Long.MaxValue` elements).
     *
     * '''Use with caution: Be warned that this may not be a good idea if the stream is infinite or its elements are very large!'''
     */
    def toStrict(atMost: FiniteDuration): immutable.Seq[I] = {
      val deadline = Deadline.now + atMost
      val b = immutable.Seq.newBuilder[I]

      @tailrec def drain(): immutable.Seq[I] =
        self.expectEvent(deadline.timeLeft) match {
          case OnError(ex) ⇒
            throw new AssertionError(s"toStrict received OnError while draining stream! Accumulated elements: ${b.result()}", ex)
          case OnComplete ⇒
            b.result()
          case OnNext(i: I @unchecked) ⇒
            b += i
            drain()
        }

      // if no subscription was obtained yet, we expect it
      if (_subscription == null) self.expectSubscription()
      _subscription.request(Long.MaxValue)

      drain()
    }

    /**
     * Execute code block while bounding its execution time between `min` and
     * `max`. `within` blocks may be nested. All methods in this trait which
     * take maximum wait times are available in a version which implicitly uses
     * the remaining time governed by the innermost enclosing `within` block.
     *
     * Note that the timeout is scaled using Duration.dilated, which uses the
     * configuration entry "akka.test.timefactor", while the min Duration is not.
     *
     * {{{
     * val ret = within(50 millis) {
     *   test ! "ping"
     *   expectMsgClass(classOf[String])
     * }
     * }}}
     */
    def within[T](min: FiniteDuration, max: FiniteDuration)(f: ⇒ T): T = probe.within(min, max)(f)

    /**
     * Same as calling `within(0 seconds, max)(f)`.
     */
    def within[T](max: FiniteDuration)(f: ⇒ T): T = probe.within(max)(f)

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

    /** Asserts that a subscription has been received or will be received */
    def ensureSubscription(): Self = {
      subscription // initializes lazy val
      this
    }

    def request(n: Long): Self = {
      subscription.request(n)
      this
    }

    /**
     * Request and expect a stream element.
     */
    def requestNext(element: T): Self = {
      subscription.request(1)
      expectNext(element)
      this
    }

    def cancel(): Self = {
      subscription.cancel()
      this
    }

    /**
     * Request and expect a stream element.
     */
    def requestNext(): T = {
      subscription.request(1)
      expectNext()
    }

    /**
     * Request and expect a stream element during the specified time or timeout.
     */
    def requestNext(d: FiniteDuration): T = {
      subscription.request(1)
      expectNext(d)
    }
  }
}

/**
 * INTERNAL API
 */
private[testkit] object StreamTestKit {
  import TestPublisher._

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

    def sendOnSubscribe(): Unit = subscriber.onSubscribe(this)
  }

  final class ProbeSource[T](val attributes: Attributes, shape: SourceShape[T])(implicit system: ActorSystem) extends SourceModule[T, TestPublisher.Probe[T]](shape) {
    override def create(context: MaterializationContext) = {
      val probe = TestPublisher.probe[T]()
      (probe, probe)
    }
    override protected def newInstance(shape: SourceShape[T]): SourceModule[T, TestPublisher.Probe[T]] = new ProbeSource[T](attributes, shape)
    override def withAttributes(attr: Attributes): Module = new ProbeSource[T](attr, amendShape(attr))
  }

  final class ProbeSink[T](val attributes: Attributes, shape: SinkShape[T])(implicit system: ActorSystem) extends SinkModule[T, TestSubscriber.Probe[T]](shape) {
    override def create(context: MaterializationContext) = {
      val probe = TestSubscriber.probe[T]()
      (probe, probe)
    }
    override protected def newInstance(shape: SinkShape[T]): SinkModule[T, TestSubscriber.Probe[T]] = new ProbeSink[T](attributes, shape)
    override def withAttributes(attr: Attributes): Module = new ProbeSink[T](attr, amendShape(attr))
  }

}
