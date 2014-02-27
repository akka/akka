package rx.async.tck

import org.testng.annotations.Test
import rx.async.spi.{ Subscription, Subscriber, Publisher }

trait SubscriberVerification[T] {
  import TestEnvironment._
  import SubscriberVerification._

  // TODO: make the timeouts be dilate-able so that one can tune the suite for the machine it runs on

  /**
   * This is the main method you must implement in your test incarnation.
   * It must create a new Subscriber instance to be subjected to the testing logic.
   *
   * In order to be meaningfully testable your Subscriber must inform the given
   * `SubscriberProbe` of the respective events having been received.
   */
  def createSubscriber(probe: SubscriberProbe[T]): Subscriber[T]

  /**
   * Helper method required for generating test elements.
   * It must create a Publisher for a stream with exactly the given number of elements.
   * If `elements` is zero the produced stream must be infinite.
   */
  def createHelperPublisher(elements: Int): Publisher[T]

  ////////////////////// TEST SETUP VERIFICATION ///////////////////////////

  @Test
  def exerciseHappyPath(): Unit =
    new TestSetup {
      puppet.triggerRequestMore(1)
      expectRequestMore(1)
      sendNextTFromUpstream()
      probe.expectNext(lastT)

      puppet.triggerRequestMore(1)
      expectRequestMore(1)
      sendNextTFromUpstream()
      probe.expectNext(lastT)

      puppet.triggerCancel()
      expectCancelling()
    }

  ////////////////////// SPEC RULE VERIFICATION ///////////////////////////

  // Subscriber::onSubscribe(Subscription), Subscriber::onNext(T)
  //   must asynchronously schedule a respective event to the subscriber
  //   must not call any methods on the Subscription, the Publisher or any other Publishers or Subscribers
  @Test
  def onSubscribeAndOnNextMustAsynchronouslyScheduleAnEvent(): Unit = {
    // cannot be meaningfully tested, or can it?
  }

  // Subscriber::onComplete, Subscriber::onError(Throwable)
  //   must asynchronously schedule a respective event to the Subscriber
  //   must not call any methods on the Subscription, the Publisher or any other Publishers or Subscribers
  //   must consider the Subscription cancelled after having received the event
  @Test
  def onCompleteAndOnErrorMustAsynchronouslyScheduleAnEvent(): Unit = {
    // cannot be meaningfully tested, or can it?
  }

  // A Subscriber
  //   must not accept an `onSubscribe` event if it already has an active Subscription
  @Test
  def mustNotAcceptAnOnSubscribeEventIfItAlreadyHasAnActiveSubscription(): Unit =
    new TestSetup {
      // try to subscribe another time, if the subscriber calls `probe.registerOnSubscribe` the test will fail
      sub.onSubscribe {
        new Subscription {
          def requestMore(elements: Int): Unit = fail(s"Subscriber $sub illegally called `subscription.requestMore($elements)`")
          def cancel(): Unit = fail(s"Subscriber $sub illegally called `subscription.cancel()`")
        }
      }
    }

  // A Subscriber
  //   must call Subscription::cancel during shutdown if it still has an active Subscription
  @Test
  def mustCallSubscriptionCancelDuringShutdownIfItStillHasAnActiveSubscription(): Unit =
    new TestSetup {
      puppet.triggerShutdown()
      expectCancelling()
    }

  // A Subscriber
  //   must ensure that all calls on a Subscription take place from the same thread or provide for respective external synchronization
  @Test
  def mustEnsureThatAllCallsOnASubscriptionTakePlaceFromTheSameThreadOrProvideExternalSync(): Unit = {
    // cannot be meaningfully tested, or can it?
  }

  // A Subscriber
  //   must be prepared to receive one or more `onNext` events after having called Subscription::cancel
  @Test
  def mustBePreparedToReceiveOneOrMoreOnNextEventsAfterHavingCalledSubscriptionCancel(): Unit =
    new TestSetup {
      puppet.triggerRequestMore(1)
      puppet.triggerCancel()
      expectCancelling()
      sendNextTFromUpstream()
    }

  // A Subscriber
  //   must be prepared to receive an `onComplete` event with a preceding Subscription::requestMore call
  @Test
  def mustBePreparedToReceiveAnOnCompleteEventWithAPrecedingSubscriptionRequestMore(): Unit =
    new TestSetup {
      puppet.triggerRequestMore(1)
      sendCompletion()
      probe.expectCompletion()
    }

  // A Subscriber
  //   must be prepared to receive an `onComplete` event without a preceding Subscription::requestMore call
  @Test
  def mustBePreparedToReceiveAnOnCompleteEventWithoutAPrecedingSubscriptionRequestMore(): Unit =
    new TestSetup {
      sendCompletion()
      probe.expectCompletion()
    }

  // A Subscriber
  //   must be prepared to receive an `onError` event with a preceding Subscription::requestMore call
  @Test
  def mustBePreparedToReceiveAnOnErrorEventWithAPrecedingSubscriptionRequestMore(): Unit =
    new TestSetup {
      puppet.triggerRequestMore(1)
      val ex = new RuntimeException("Test exception")
      sendError(ex)
      probe.expectError(ex)
    }

  // A Subscriber
  //   must be prepared to receive an `onError` event without a preceding Subscription::requestMore call
  @Test
  def mustBePreparedToReceiveAnOnErrorEventWithoutAPrecedingSubscriptionRequestMore(): Unit =
    new TestSetup {
      val ex = new RuntimeException("Test exception")
      sendError(ex)
      probe.expectError(ex)
    }

  // A Subscriber
  //   must make sure that all calls on its `onXXX` methods happen-before the processing of the respective events
  @Test
  def mustMakeSureThatAllCallsOnItsMethodsHappenBeforeTheProcessingOfTheRespectiveEvents(): Unit = {
    // cannot be meaningfully tested, or can it?
  }

  /////////////////////// ADDITIONAL "COROLLARY" TESTS //////////////////////

  /////////////////////// TEST INFRASTRUCTURE //////////////////////

  abstract class TestSetup extends ManualPublisher[T] {
    val tees = newManualSubscriber(createHelperPublisher(0)) // gives us access to an infinite stream of T values
    val probe = new Probe
    var lastT: T = _
    subscribe(createSubscriber(probe))
    def sub = subscriber.get
    probe.puppet.expectCompletion(timeoutMillis = 100, errorMsg = s"Subscriber $sub did not `registerOnSubscribe`")

    def puppet: SubscriberPuppet = probe.puppet.value
    def sendNextTFromUpstream(): Unit = sendNext(nextT())
    def nextT(): T = {
      lastT = tees.requestNextElement()
      lastT
    }

    class Probe extends SubscriberProbe[T] {
      val puppet = new Promise[SubscriberPuppet]
      val elements = new Receptacle[T]
      val completed = new Latch
      val error = new Promise[Throwable]
      def registerOnSubscribe(p: SubscriberPuppet): Unit =
        if (!puppet.isCompleted) puppet.complete(p)
        else fail(s"Subscriber $sub illegally accepted a second Subscription")
      def registerOnNext(element: T): Unit = elements.add(element)
      def registerOnComplete(): Unit = completed.close()
      def registerOnError(cause: Throwable): Unit = error.complete(cause)
      def expectNext(expected: T, timeoutMillis: Int = 100) = {
        val received = elements.next(timeoutMillis, s"Subscriber $sub did not call `registerOnNext($expected)`")
        if (received != expected)
          fail(s"Subscriber $sub called `registerOnNext($received)` rather than `registerOnNext($expected)`")
      }
      def expectCompletion(timeoutMillis: Int = 100): Unit =
        completed.expectClose(timeoutMillis, s"Subscriber $sub did not call `registerOnComplete()`")
      def expectError(expected: Throwable, timeoutMillis: Int = 100): Unit = {
        error.expectCompletion(timeoutMillis, s"Subscriber $sub did not call `registerOnError($expected)`")
        if (error.value != expected)
          fail(s"Subscriber $sub called `registerOnError(${error.value})` rather than `registerOnError($expected)`")
      }
    }
  }
}

object SubscriberVerification {
  trait SubscriberProbe[T] {
    /**
     * Must be called by the test subscriber when it has received the `onSubscribe` event.
     */
    def registerOnSubscribe(puppet: SubscriberPuppet): Unit

    /**
     * Must be called by the test subscriber when it has received an`onNext` event.
     */
    def registerOnNext(element: T): Unit

    /**
     * Must be called by the test subscriber when it has received an `onComplete` event.
     */
    def registerOnComplete(): Unit

    /**
     * Must be called by the test subscriber when it has received an `onError` event.
     */
    def registerOnError(cause: Throwable): Unit
  }

  trait SubscriberPuppet {
    def triggerShutdown(): Unit
    def triggerRequestMore(elements: Int): Unit
    def triggerCancel(): Unit
  }
}