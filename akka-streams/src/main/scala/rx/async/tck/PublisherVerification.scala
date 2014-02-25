package rx.async.tck

import java.lang.ref.{ WeakReference, ReferenceQueue }
import org.testng.annotations.Test
import org.testng.Assert._
import rx.async.spi.{ Subscription, Publisher }

abstract class PublisherVerification[T] extends TestEnvironment {
  import TestEnvironment._

  // TODO: make the timeouts be dilate-able so that one can tune the suite for the machine it runs on

  /**
   * This is the main method you must implement in your test incarnation.
   * It must create a Publisher for a stream with exactly the given number of elements.
   * If `elements` is zero the produced stream must be infinite.
   */
  def createPublisher(elements: Int): Publisher[T]

  /**
   * Override in your test if you want to enable error-state verification.
   * If you don't override the respective tests will be ignored.
   */
  def createCompletedStatePublisher(): Publisher[T] = null

  /**
   * Override in your test if you want to enable error-state verification.
   * If you don't override the respective tests will be ignored.
   */
  def createErrorStatePublisher(): Publisher[T] = null

  ////////////////////// TEST SETUP VERIFICATION ///////////////////////////

  @Test
  def createPublisher3MustProduceAStreamOfExactly3Elements(): Unit =
    activePublisherTest(elements = 3) { pub ⇒
      val sub = newManualSubscriber(pub)
      def requestNextElementOrEndOfStream() =
        sub.requestNextElementOrEndOfStream(errorMsg = s"Timeout while waiting for next element from Publisher $pub")
      assertTrue(requestNextElementOrEndOfStream().isDefined, s"Publisher $pub produced no elements")
      assertTrue(requestNextElementOrEndOfStream().isDefined, s"Publisher $pub produced only 1 element")
      assertTrue(requestNextElementOrEndOfStream().isDefined, s"Publisher $pub produced only 2 elements")
      sub.expectNone(errorMsg = x ⇒ s"`createPublisher(3)` created stream of more than 3 elements (4th element was $x)")
    }

  ////////////////////// SPEC RULE VERIFICATION ///////////////////////////

  // Publisher::subscribe(Subscriber)
  //   when Publisher is in `completed` state
  //     must not call `onSubscribe` on the given Subscriber
  //     must trigger a call to `onComplete` on the given Subscriber
  @Test
  def publisherSubscribeWhenCompletedMustTriggerOnCompleteAndNotOnSubscribe(): Unit =
    completedPublisherTest { pub ⇒
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
      Thread.sleep(100) // wait for the Publisher to potentially call 'onSubscribe' or `onNext` which would trigger an async error
    }

  // Publisher::subscribe(Subscriber)
  //   when Publisher is in `error` state
  //     must not call `onSubscribe` on the given Subscriber
  //     must trigger a call to `onError` on the given Subscriber
  @Test
  def publisherSubscribeWhenInErrorStateMustTriggerOnErrorAndNotOnSubscribe(): Unit =
    errorPublisherTest { pub ⇒
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
      Thread.sleep(100) // wait for the Publisher to potentially call 'onSubscribe' or `onNext` which would trigger an async error
    }

  // Publisher::subscribe(Subscriber)
  //   when Publisher is in `shut-down` state
  //     must not call `onSubscribe` on the given Subscriber
  //     must trigger a call to `onError` with a `java.lang.IllegalStateException` on the given Subscriber
  // Subscription::cancel
  //   when Subscription is not cancelled
  //     the Publisher must shut itself down if the given Subscription is the last downstream Subscription
  @Test
  def publisherSubscribeWhenInShutDownStateMustTriggerOnErrorAndNotOnSubscribe(): Unit =
    activePublisherTest(3) { pub ⇒
      val sub = newManualSubscriber(pub)
      sub.cancel()

      val latch = new Latch()
      pub.subscribe {
        new TestSubscriber[T] {
          override def onError(cause: Throwable): Unit = {
            latch.assertOpen(s"shut-down-state Publisher $pub called `onError` twice on new Subscriber")
            latch.close()
          }
        }
      }
      latch.expectClose(timeoutMillis = 100, s"shut-down-state Publisher $pub did not call `onError` on new Subscriber")
      Thread.sleep(100) // wait for the Publisher to potentially call 'onSubscribe' or `onNext` which would trigger an async error
    }

  // Publisher::subscribe(Subscriber)
  //   when Publisher is neither in `completed` nor `error` state
  //     must trigger a call to `onSubscribe` on the given Subscriber if the Subscription is to be accepted
  @Test
  def publisherSubscribeWhenActiveMustCallOnSubscribeFirst(): Unit =
    activePublisherTest(elements = 1) { pub ⇒
      val latch = new Latch()
      pub.subscribe {
        new TestSubscriber[T] {
          override def onSubscribe(subscription: Subscription): Unit = latch.close()
        }
      }
      latch.expectClose(timeoutMillis = 100, s"Active Publisher $pub did not call `onSubscribe` on new subscription request")
    }

  // Publisher::subscribe(Subscriber)
  //   when Publisher is neither in `completed` nor `error` state
  //     must trigger a call to `onError` on the given Subscriber if the Subscription is to be rejected
  //     must reject the Subscription if the same Subscriber already has an active Subscription
  @Test
  def publisherSubscribeWhenActiveMustRejectDoubleSubscription(): Unit =
    activePublisherTest(elements = 1) { pub ⇒
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
    }

  // Subscription::requestMore(Int)
  //   when Subscription is cancelled
  //     must ignore the call
  @Test
  def subscriptionRequestMoreWhenCancelledMustIgnoreTheCall(): Unit =
    activePublisherTest(elements = 1) { pub ⇒
      val sub = newManualSubscriber(pub)
      sub.subscription.value.cancel() // first time must succeed
      sub.subscription.value.cancel() // the second time must not throw
    }

  // Subscription::requestMore(Int)
  //   when Subscription is not cancelled
  //     must register the given number of additional elements to be produced to the respective subscriber
  // A Publisher
  //   must not call `onNext`
  //    more times than the total number of elements that was previously requested with Subscription::requestMore by the corresponding subscriber
  @Test
  def subscriptionRequestMoreMustResultInTheCorrectNumberOfProducedElements(): Unit =
    activePublisherTest(elements = 5) { pub ⇒
      val sub = newManualSubscriber(pub)

      sub.expectNone(errorMsg = x ⇒ s"Publisher $pub produced $x before the first `requestMore`")
      sub.requestMore(1)
      sub.nextElement(errorMsg = s"Publisher $pub produced no element after first `requestMore`")

      sub.requestMore(1)
      sub.requestMore(2)
      sub.nextElements(3, timeoutMillis = 100, s"Publisher $pub produced less than 3 elements after two respective `requestMore` calls")

      sub.expectNone(errorMsg = x ⇒ s"Publisher $pub produced unrequested $x")
    }

  // Subscription::requestMore(Int)
  //   when Subscription is not cancelled
  //     must throw a `java.lang.IllegalArgumentException` if the argument is <= 0
  @Test
  def subscriptionRequestMoreMustThrowIfArgumentIsNonPositive(): Unit =
    activePublisherTest(elements = 1) { pub ⇒
      val sub = newManualSubscriber(pub)
      expectThrowingOf[IllegalArgumentException](s"Calling `requestMore(-1)` a subscription to $pub did not fail with an `IllegalArgumentException`") {
        sub.subscription.value.requestMore(-1)
      }
      expectThrowingOf[IllegalArgumentException](s"Calling `requestMore(0)` a subscription to $pub did not fail with an `IllegalArgumentException`") {
        sub.subscription.value.requestMore(0)
      }
    }

  // Subscription::cancel
  //   when Subscription is cancelled
  //     must ignore the call
  @Test
  def subscriptionCancelWhenCancelledMustIgnoreCall(): Unit =
    activePublisherTest(elements = 1) { pub ⇒
      val sub = newManualSubscriber(pub)
      sub.subscription.value.cancel()
      sub.subscription.value.requestMore(1) // must not throw
    }

  // Subscription::cancel
  //   when Subscription is not cancelled
  //     the Publisher must eventually cease to call any methods on the corresponding subscriber
  @Test
  def onSubscriptionCancelThePublisherMustEventuallyCeaseToCallAnyMethodsOnTheSubscriber(): Unit = {
    // cannot be meaningfully tested, or can it?
  }

  // Subscription::cancel
  //   when Subscription is not cancelled
  //     the Publisher must eventually drop any references to the corresponding subscriber
  @Test
  def onSubscriptionCancelThePublisherMustEventuallyDropAllReferencesToTheSubscriber(): Unit = {
    activePublisherTest(elements = 3) { pub ⇒
      var sub = newManualSubscriber(pub)
      val ref = new WeakReference(sub, new ReferenceQueue[ManualSubscriber[T]]())
      sub.requestMore(1)
      sub.cancel()
      sub = null
      System.gc()
      if (!ref.isEnqueued)
        fail(s"Publisher $pub did not drop reference to test subscriber after subscription cancellation")
    }
  }

  // Subscription::cancel
  //   when Subscription is not cancelled
  //     the Publisher must obey the "a Subscription::cancel happens before any subsequent Publisher::subscribe" rule
  @Test
  def onSubscriptionCancelThePublisherMustObeyCancelHappensBeforeSubsequentSubscribe(): Unit = {
    activePublisherTest(elements = 3) { pub ⇒
      val keeper = newManualSubscriber(pub) // required to prevent the publisher from shutting down
      val sub = newManualSubscriber(pub)
      for (i ← 1 to 100) {
        // try to cancel and resubscribe very quickly, in order to potentially trigger an "overtaking"
        sub.cancel()
        subscribe(pub, sub)
      }
    }
  }

  // A Publisher
  //   must not call `onNext`
  //     after having issued an `onComplete` or `onError` call on a subscriber
  @Test
  def mustNotCallOnNextAfterHavingIssuedAnOnCompleteOrOnErrorCallOnASubscriber(): Unit = {
    // this is implicitly verified by the test infrastructure
  }

  // A Publisher
  //   must produce the same elements in the same sequence for all its subscribers
  @Test
  def mustProduceTheSameElementsInTheSameSequenceForAllItsSubscribers(): Unit =
    activePublisherTest(elements = 5) { pub ⇒
      val sub1 = newManualSubscriber(pub)
      val sub2 = newManualSubscriber(pub)
      val sub3 = newManualSubscriber(pub)

      sub1.requestMore(1)
      val x1 = sub1.nextElement(errorMsg = s"Publisher $pub did not produce the requested 1 element on 1st subscriber")
      sub2.requestMore(2)
      val y1 = sub2.nextElements(2, errorMsg = s"Publisher $pub did not produce the requested 2 elements on 2nd subscriber")
      sub1.requestMore(1)
      val x2 = sub1.nextElement(errorMsg = s"Publisher $pub did not produce the requested 1 element on 1st subscriber")
      sub3.requestMore(3)
      val z1 = sub3.nextElements(3, errorMsg = s"Publisher $pub did not produce the requested 3 elements on 3rd subscriber")
      sub3.requestMore(1)
      val z2 = sub3.nextElement(errorMsg = s"Publisher $pub did not produce the requested 1 element on 3rd subscriber")
      sub3.requestMore(1)
      val z3 = sub3.nextElement(errorMsg = s"Publisher $pub did not produce the requested 1 element on 3rd subscriber")
      sub3.expectCompletion(errorMsg = s"Publisher $pub did not complete the stream as expected on 3rd subscriber")
      sub2.requestMore(3)
      val y2 = sub2.nextElements(3, errorMsg = s"Publisher $pub did not produce the requested 3 elements on 2nd subscriber")
      sub2.expectCompletion(errorMsg = s"Publisher $pub did not complete the stream as expected on 2nd subscriber")
      sub1.requestMore(2)
      val x3 = sub1.nextElements(2, errorMsg = s"Publisher $pub did not produce the requested 2 elements on 1st subscriber")
      sub1.requestMore(1)
      val x4 = sub1.nextElement(errorMsg = s"Publisher $pub did not produce the requested 1 element on 1st subscriber")
      sub1.expectCompletion(errorMsg = s"Publisher $pub did not complete the stream as expected on 1st subscriber")

      val r = x1 :: x2 :: x3.toList ::: x4 :: Nil
      assertEquals(r, y1.toList ::: y2.toList, s"Publisher $pub did not produce the same element sequence for subscribers 1 and 2")
      assertEquals(r, z1.toList ::: z2 :: z3 :: Nil, s"Publisher $pub did not produce the same element sequence for subscribers 1 and 3")
    }

  // A Publisher
  //   must support a pending element count up to 2^63-1 (Long.MAX_VALUE) and provide for overflow protection
  @Test
  def mustSupportAPendingElementCountUpToLongMaxValueAndProviderForOverflowProtection(): Unit = {
    // not really testable without more control over the Publisher,
    // we verify this part of the fanout logic with the IdentityProcessorVerification
  }

  // A Publisher
  //   must call `onComplete` on a subscriber after having produced the final stream element to it
  //   must call `onComplete` on a subscriber at the earliest possible point in time
  @Test
  def mustCallOnCompleteOnASubscriberAfterHavingProducedTheFinalStreamElementToIt(): Unit = {
    activePublisherTest(elements = 3) { pub ⇒
      val sub = newManualSubscriber(pub)
      sub.requestNextElement()
      sub.requestNextElement()
      sub.requestNextElement()
      sub.expectCompletion(errorMsg = s"Publisher $pub did not complete the stream immediately after the final element")
      sub.expectNone()
    }
  }

  // A Publisher
  //   must start producing with the oldest still available element for a new subscriber
  @Test
  def mustStartProducingWithTheOldestStillAvailableElementForASubscriber(): Unit = {
    // can only be properly tested if we know more about the Producer implementation
    // like buffer size and buffer retention logic
  }

  // A Publisher
  //   must call `onError` on all its subscribers if it encounters a non-recoverable error
  @Test
  def mustCallOnErrorOnAllItsSubscribersIfItEncountersANonRecoverableError(): Unit = {
    // not really testable without more control over the Publisher,
    // we verify this part of the fanout logic with the IdentityProcessorVerification
  }

  // A Publisher
  //   must not call `onComplete` or `onError` more than once per subscriber
  @Test
  def mustNotCallOnCompleteOrOnErrorMoreThanOncePerSubscriber(): Unit = {
    // this is implicitly verified by the test infrastructure
  }

  // A Publisher
  //   must shut itself down if its last downstream Subscription has been cancelled
  @Test
  def mustShutItselfDownIfItsLastDownstreamSubscriptionHasBeenCancelled(): Unit = {
    // cannot be meaningfully tested, or can it?
  }

  /////////////////////// ADDITIONAL "COROLLARY" TESTS //////////////////////

  /////////////////////// TEST INFRASTRUCTURE //////////////////////

  def activePublisherTest[U](elements: Int)(body: Publisher[T] ⇒ U): Unit = {
    val pub = createPublisher(elements)
    body(pub)
    verifyNoAsyncErrors()
  }

  def completedPublisherTest[U](body: Publisher[T] ⇒ U): Unit =
    potentiallyPendingTest(createCompletedStatePublisher(), body)

  def errorPublisherTest[U](body: Publisher[T] ⇒ U): Unit =
    potentiallyPendingTest(createErrorStatePublisher(), body)

  def potentiallyPendingTest[U](pub: Publisher[T], body: Publisher[T] ⇒ U): Unit =
    if (pub ne null) {
      body(pub)
      verifyNoAsyncErrors()
    } // else test is pending/ignored, which our great Java test frameworks have no (non-static) concept for

}