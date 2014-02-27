package rx.async.tck

import rx.async.api.Processor
import rx.async.spi.{ Subscription, Subscriber, Publisher }
import org.testng.annotations.Test
import rx.async.tck.SubscriberVerification.SubscriberProbe

abstract class IdentityProcessorVerification[T] extends PublisherVerification[T] with SubscriberVerification[T] { verification ⇒
  import TestEnvironment._

  // TODO: make the timeouts be dilate-able so that one can tune the suite for the machine it runs on

  /**
   * This is the main method you must implement in your test incarnation.
   * It must create a Processor, which simply forwards all stream elements from its upstream
   * to its downstream. It is not allowed to internally buffer more than the given number of elements.
   */
  def createIdentityProcessor(maxBufferSize: Int): Processor[T, T]

  /**
   * Helper method required for running the Publisher rules against a Processor.
   * It must create a Publisher for a stream with exactly the given number of elements.
   * If `elements` is zero the produced stream must be infinite.
   */
  def createHelperPublisher(elements: Int): Publisher[T]

  ////////////////////// PUBLISHER RULES VERIFICATION ///////////////////////////

  // A Processor
  //   must obey all Publisher rules on its producing side
  def createPublisher(elements: Int): Publisher[T] = {
    val processor = createIdentityProcessor(maxBufferSize = 16)
    val pub = createHelperPublisher(elements)
    pub.subscribe(processor.getSubscriber)
    processor.getPublisher // we run the PublisherVerification against this
  }

  // A Publisher
  //   must support a pending element count up to 2^63-1 (Long.MAX_VALUE) and provide for overflow protection
  @Test
  override def mustSupportAPendingElementCountUpToLongMaxValueAndProviderForOverflowProtection(): Unit =
    new TestSetup(maxBufferSize = 1) {
      val sub = newSubscriber()
      sub.requestMore(Int.MaxValue)
      sub.requestMore(Int.MaxValue)
      sub.requestMore(2) // if the Subscription only keeps an Int counter without overflow protection it will now be at zero

      sendNextTFromUpstream()
      expectNextElement(sub, lastT)

      sendNextTFromUpstream()
      expectNextElement(sub, lastT)

      verifyNoAsyncErrors()
    }

  // A Publisher
  //   must call `onError` on all its subscribers if it encounters a non-recoverable error
  @Test
  override def mustCallOnErrorOnAllItsSubscribersIfItEncountersANonRecoverableError(): Unit =
    new TestSetup(maxBufferSize = 1) {
      val sub1 = new ManualSubscriber[T] with SubscriptionSupport[T] with ErrorCollection[T]
      verification.subscribe(processor.getPublisher, sub1)
      val sub2 = new ManualSubscriber[T] with SubscriptionSupport[T] with ErrorCollection[T]
      verification.subscribe(processor.getPublisher, sub2)

      sub1.requestMore(1)
      expectRequestMore(1)
      sendNextTFromUpstream()
      expectNextElement(sub1, lastT)
      sub1.requestMore(1)

      // sub1 now has received and element and has 1 pending
      // sub2 has not yet requested anything

      val ex = new RuntimeException
      sendError(ex)
      sub1.expectError(ex)
      sub2.expectError(ex)

      verifyNoAsyncErrors()
    }

  ////////////////////// SUBSCRIBER RULES VERIFICATION ///////////////////////////

  // A Processor
  //   must obey all Subscriber rules on its consuming side
  def createSubscriber(probe: SubscriberProbe[T]): Subscriber[T] = {
    val processor = createIdentityProcessor(maxBufferSize = 2)
    processor.getPublisher.subscribe {
      new Subscriber[T] {
        def onSubscribe(subscription: Subscription): Unit =
          probe.registerOnSubscribe {
            new SubscriberVerification.SubscriberPuppet {
              def triggerShutdown(): Unit = subscription.cancel() // TODO: improve
              def triggerRequestMore(elements: Int): Unit = subscription.requestMore(elements)
              def triggerCancel(): Unit = subscription.cancel()
            }
          }
        def onNext(element: T): Unit = probe.registerOnNext(element)
        def onComplete(): Unit = probe.registerOnComplete()
        def onError(cause: Throwable): Unit = probe.registerOnError(cause)
      }
    }
    processor.getSubscriber // we run the SubscriberVerification against this
  }

  ////////////////////// OTHER SPEC RULE VERIFICATION ///////////////////////////

  // A Processor
  //   must cancel its upstream Subscription if its last downstream Subscription has been cancelled
  @Test
  def mustCancelItsUpstreamSubscriptionIfItsLastDownstreamSubscriptionHasBeenCancelled(): Unit =
    new TestSetup(maxBufferSize = 1) {
      val sub = newSubscriber()
      sub.cancel()
      expectCancelling()

      verifyNoAsyncErrors()
    }

  // A Processor
  //   must immediately pass on `onError` events received from its upstream to its downstream
  @Test
  def mustImmediatelyPassOnOnErrorEventsReceivedFromItsUpstreamToItsDownstream(): Unit =
    new TestSetup(maxBufferSize = 1) {
      val sub = new ManualSubscriber[T] with SubscriptionSupport[T] with ErrorCollection[T]
      verification.subscribe(processor.getPublisher, sub)

      val ex = new RuntimeException
      sendError(ex)
      sub.expectError(ex)

      verifyNoAsyncErrors()
    }

  // A Processor
  //   must be prepared to receive incoming elements from its upstream even if a downstream subscriber has not requested anything yet
  @Test
  def mustBePreparedToReceiveIncomingElementsFromItsUpstreamEvenIfADownstreamSubscriberHasNotRequestedYet(): Unit =
    new TestSetup(maxBufferSize = 4) {
      val sub = newSubscriber()
      sendNextTFromUpstream()
      sub.expectNone(withinMillis = 50)
      sendNextTFromUpstream()
      sub.expectNone(withinMillis = 50)

      verifyNoAsyncErrors()
    }

  /////////////////////// ADDITIONAL "COROLLARY" TESTS //////////////////////

  @Test // trigger `requestFromUpstream` for elements that have been requested 'long ago'
  def mustRequestFromUpstreamForElementsThatHaveBeenRequestedLongAgo(): Unit =
    new TestSetup(maxBufferSize = 1) {
      val sub1 = newSubscriber()
      sub1.requestMore(5)

      expectRequestMore(1)
      sendNextTFromUpstream()
      expectNextElement(sub1, lastT)

      expectRequestMore(1)
      sendNextTFromUpstream()
      expectNextElement(sub1, lastT)

      expectRequestMore(1)

      val sub2 = newSubscriber()

      // sub1 now has 3 pending
      // sub2 has 0 pending

      sendNextTFromUpstream()
      expectNextElement(sub1, lastT)
      sub2.expectNone() // since sub2 hasn't requested anything yet

      sub2.requestMore(1)
      expectNextElement(sub2, lastT)

      expectRequestMore(1) // because sub1 still has 2 pending

      verifyNoAsyncErrors()
    }

  @Test // unblock the stream if a 'blocking' subscription has been cancelled
  def mustUnblockTheStreamIfABlockingSubscriptionHasBeenCancelled(): Unit =
    new TestSetup(maxBufferSize = 1) {
      val sub1 = newSubscriber()
      val sub2 = newSubscriber()

      sub1.requestMore(5)
      expectRequestMore(1)
      sendNextTFromUpstream()

      expectNoRequestMore() // because we only have buffer size 1 and sub2 hasn't seen the first value yet
      sub2.cancel() // must "unblock"
      expectRequestMore(1)

      verifyNoAsyncErrors()
    }

  /////////////////////// TEST INFRASTRUCTURE //////////////////////

  abstract class TestSetup(maxBufferSize: Int) extends ManualPublisher[T] {
    val tees = newManualSubscriber(createPublisher(0)) // gives us access to an infinite stream of T values
    val processor = createIdentityProcessor(maxBufferSize)
    var lastT: T = _
    subscribe(processor.getSubscriber)

    def newSubscriber() = newManualSubscriber(processor.getPublisher)
    def sendNextTFromUpstream(): Unit = sendNext(nextT())
    def nextT(): T = {
      lastT = tees.requestNextElement()
      lastT
    }
    def expectNextElement(sub: ManualSubscriber[T], expected: T): Unit = {
      val elem = sub.nextElement()
      if (elem != expected) fail(s"Received `onNext($elem)` on downstream but expected `onNext($expected)`")
    }
  }
}