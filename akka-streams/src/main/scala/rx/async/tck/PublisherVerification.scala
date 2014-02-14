package rx.async.tck

import org.junit.Test
import org.junit.Assert._
import org.junit.Assume._
import scala.util.control.NonFatal
import rx.async.spi.{ Subscription, Publisher }

abstract class PublisherVerification[T] extends TestCaseEnvironment {
  import TestCaseEnvironment._

  /**
   * This is the main method you must implement in your test incarnation.
   * It must produce a Publisher for a stream with exactly the given number of elements.
   * Note that, if `elements` is zero, the created Publisher must already be in completed state.
   */
  def createPublisher(elements: Int): Publisher[T]

  /**
   * Override in your test if you want to enable error-state verification.
   * If you don't override the respective tests will be ignored.
   */
  def createErrorStatePublisher(): Publisher[T] = null

  @Test
  def createPublisher3MustProduceAStreamOfExactly3Elements(): Unit = {
    val pub = createPublisher(elements = 3)
    val sub = new FullManualSubscriber[T]
    subscribe(pub, sub)
    def requestAndExpectOneOrEndOfStream() =
      sub.requestAndExpectOneOrEndOfStream(timeoutMillis = 100, s"Timeout while waiting for next element from Publisher $pub")
    assertTrue(s"Publisher $pub produced no elements", requestAndExpectOneOrEndOfStream().isDefined)
    assertTrue(s"Publisher $pub produced only 1 element", requestAndExpectOneOrEndOfStream().isDefined)
    assertTrue(s"Publisher $pub produced only 2 elements", requestAndExpectOneOrEndOfStream().isDefined)
    sub.expectNone(withinMillis = 100, x ⇒ s"`createPublisher(5)` created stream of more than 3 elements (4th element was $x)")
    verifyNoAsyncErrors()
  }

  @Test
  def publisherSubscribeWhenCompletedMustTriggerOnCompleteAndNotOnSubscribe(): Unit = {
    val pub = createPublisher(elements = 0)
    val latch = new Latch()
    pub.subscribe {
      new TestSubscriber[T] {
        override def onComplete(): Unit = {
          latch.assertOpen(s"Publisher $pub called `onComplete` twice on new Subscriber")
          latch.close()
        }
        override def onSubscribe(subscription: Subscription): Unit =
          fail(s"Publisher created by `createPublisher(0)` ($pub) called `onSubscribe` on new Subscriber")
      }
    }
    latch.expectClose(timeoutMillis = 100, s"Publisher created by `createPublisher(0)` ($pub) did not call `onComplete` on new Subscriber")
    Thread.sleep(100) // wait for the Publisher to potentially call 'onSubscribe' which would trigger an async error
    verifyNoAsyncErrors() // also verifies that `onNext` is not called after completion
  }

  @Test
  def publisherSubscribeWhenInErrorStateMustTriggerOnErrorAndNotOnSubscribe(): Unit = {
    val pub = createErrorStatePublisher()
    assumeTrue(pub ne null) // causes this test to be ignored if condition is not satisfied
    val latch = new Latch()
    pub.subscribe {
      new TestSubscriber[T] {
        override def onError(cause: Throwable): Unit = {
          latch.assertOpen(s"Error-state Publisher $pub called `onError` twice on new Subscriber")
          latch.close()
        }
      }
    }
    latch.expectClose(timeoutMillis = 100, s"Error-state Publisher $pub did not call `onError` on new Subscriber")
    Thread.sleep(100) // wait for the Publisher to potentially call 'onSubscribe' which would trigger an async error
    verifyNoAsyncErrors() // also verifies that `onNext` is not called after `onError`
  }

  @Test
  def publisherSubscribeWhenActiveMustCallOnSubscribeFirst(): Unit = {
    val pub = createPublisher(1)
    val latch = new Latch()
    pub.subscribe {
      new TestSubscriber[T] {
        override def onSubscribe(subscription: Subscription): Unit = latch.close()
      }
    }
    latch.expectClose(timeoutMillis = 100, s"Active Publisher $pub did not call `onSubscribe` on new subscription request")
    verifyNoAsyncErrors()
  }

  @Test
  def publisherSubscribeWhenActiveMustRejectDoubleSubscription(): Unit = {
    val pub = createPublisher(1)
    val latch = new Latch()
    val errorCause = new Promise[Throwable]
    val sub = new TestSubscriber[T] {
      override def onSubscribe(subscription: Subscription): Unit = latch.close()
      override def onError(cause: Throwable): Unit = errorCause.complete(cause)
    }
    pub.subscribe(sub)
    latch.expectClose(timeoutMillis = 100, s"Active Publisher $pub did not call `onSubscribe` on first subscription request")
    errorCause.assertUncompleted(s"Active Publisher $pub unexpectedly called `onError` on first subscription request")

    latch.reOpen()
    pub.subscribe(sub)
    errorCause.expectCompletion(timeoutMillis = 100, s"Active Publisher $pub did not call `onError` on double subscription request")
    if (!errorCause.value.isInstanceOf[IllegalStateException])
      fail(s"Publisher $pub called `onError` with ${errorCause.value} rather than an `IllegalStateException` on double subscription request")
    latch.assertOpen(s"Active Publisher $pub unexpectedly called `onSubscribe` on double subscription request")
    verifyNoAsyncErrors()
  }

  @Test
  def subscriptionRequestMoreWhenCancelledMustThrow(): Unit = {
    val pub = createPublisher(1)
    val sub = new TestSubscriber[T] with SubscriptionSupport[T]
    subscribe(pub, sub)

    sub.subscription.value.cancel() // first time must succeed
    try sub.subscription.value.cancel()
    catch {
      case _: IllegalStateException ⇒ // ok
      case NonFatal(e)              ⇒ fail(s"Cancelling a subscription to $pub twice did not fail with an `IllegalStateException` but $e")
    }
  }

  // this test tests two rules at the same time:
  // - Subscription::requestMore, when subscription is not cancelled,
  //   must register N additional elements to be published to the respective Subscriber
  // - A Publisher must not call `onNext` more times than the total number of elements
  //   that was previously requested with `subscription.requestMore`
  @Test
  def subscriptionRequestMoreWhenUncancelledMustResultInTheCorrectNumberOfProducedElements(): Unit = {
    val pub = createPublisher(5)
    val sub = new FullManualSubscriber[T]
    subscribe(pub, sub)

    sub.expectNone(withinMillis = 100, x ⇒ s"Publisher $pub produced $x before the first `requestMore`")
    sub.requestAndExpectOne(timeoutMillis = 100, s"Publisher $pub produced no element after first `requestMore`")

    sub.subscription.value.requestMore(1)
    sub.subscription.value.requestMore(2)
    sub.received.expectN(3, timeoutMillis = 100, s"Publisher $pub produced less than 3 elements after two respective `requestMore` calls")

    sub.expectNone(withinMillis = 100, x ⇒ s"Publisher $pub produced unrequested $x")

    verifyNoAsyncErrors()
  }

  @Test
  def subscriptionRequestMoreWhenUncancelledMustThrowIfArgumentIsNonPositive(): Unit = {
    val pub = createPublisher(1)
    val sub = new FullManualSubscriber[T]
    subscribe(pub, sub)
    try sub.subscription.value.requestMore(-1)
    catch {
      case _: IllegalArgumentException ⇒ // ok
      case NonFatal(e)                 ⇒ fail(s"Calling `requestMore(-1)` a subscription to $pub did not fail with an `IllegalArgumentException` but $e")
    }
    try sub.subscription.value.requestMore(0)
    catch {
      case _: IllegalArgumentException ⇒ // ok
      case NonFatal(e)                 ⇒ fail(s"Calling `requestMore(0)` a subscription to $pub did not fail with an `IllegalArgumentException` but $e")
    }
    verifyNoAsyncErrors()
  }

  @Test
  def mustProduceTheSameElementsInTheSameSequenceForAllSimultaneouslySubscribedSubscribers(): Unit = {
    val pub = createPublisher(5)
    val sub1 = new FullManualSubscriber[T]
    subscribe(pub, sub1)
    val sub2 = new FullManualSubscriber[T]
    subscribe(pub, sub2)
    val sub3 = new FullManualSubscriber[T]
    subscribe(pub, sub3)

    sub1.requestOne()
    sub2.requestN(2)
    sub1.requestOne()
    sub3.requestN(3)
    sub3.requestOne()
    sub3.requestOne()
    sub2.requestN(3)
    sub1.requestN(2)
    sub1.requestOne()

    val x = sub1.expectN(5, timeoutMillis = 100, s"Publisher $pub did not produce the requested 5 elements on 1st subscriber")
    val y = sub2.expectN(5, timeoutMillis = 100, s"Publisher $pub did not produce the requested 5 elements on 2nd subscriber")
    val z = sub3.expectN(5, timeoutMillis = 100, s"Publisher $pub did not produce the requested 5 elements on 3rd subscriber")

    assertEquals(s"Publisher $pub did not produce the same element sequence for subscribers 1 and 2", x, y)
    assertEquals(s"Publisher $pub did not produce the same element sequence for subscribers 1 and 3", x, z)

    sub1.expectNone(withinMillis = 100, x ⇒ s"Publisher $pub produced unrequested $x on 1st subscriber")
    sub2.expectNone(withinMillis = 100, x ⇒ s"Publisher $pub produced unrequested $x on 2nd subscriber")
    sub3.expectNone(withinMillis = 100, x ⇒ s"Publisher $pub produced unrequested $x on 3rd subscriber")

    verifyNoAsyncErrors()
  }

  @Test
  def mustStartProducingWithTheOldestStillAvailableElementForANewlySubscribedSubscriber(): Unit = {
    // can only be properly tested if we know more about the Producer implementation
    // like buffer size and buffer retention logic
  }
}