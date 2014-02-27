package rx.async.tck

import rx.async.api.Processor
import rx.async.spi.Publisher
import org.testng.annotations.Test

abstract class IdentityProcessorVerification[T] extends PublisherVerification[T] {
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

  ////////////////////// SPEC RULE VERIFICATION ///////////////////////////

  // A Processor
  //   must obey all Publisher rules on its producing side
  def createPublisher(elements: Int): Publisher[T] = {
    val processor = createIdentityProcessor(maxBufferSize = 16)
    val pub = createHelperPublisher(elements)
    pub.subscribe(processor.getSubscriber)
    processor.getPublisher // we run the PublisherVerification against this
  }

  // A Processor
  //   must cancel its upstream Subscription if its last downstream Subscription has been cancelled
  @Test
  def mustCancelItsUpstreamSubscriptionIfItsLastDownstreamSubscriptionHasBeenCancelled(): Unit = {
    // TODO
  }

  // A Processor
  //   must immediately pass on `onError` events received from its upstream to its downstream
  @Test
  def mustImmediatelyPassOnOnErrorEventsReceivedFromItsUpstreamToItsDownstream(): Unit = {
    // TODO
  }

  // A Processor
  //   must be prepared to receive incoming elements from its upstream even if a downstream subscriber has not requested anything yet
  @Test
  def mustBePreparedToReceiveIncomingElementsFromItsUpstreamEvenIfADownstreamSubscriberHasNotRequestedYet(): Unit = {
    // TODO
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

  class TestSetup(maxBufferSize: Int) extends ManualPublisher[T] {
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
    def expectRequestMore(expected: Int): Unit = {
      val requested = nextRequestMore()
      if (requested != expected) fail(s"Received `requestMore(requested)` on upstream but expected `requestMore($expected)`")
    }
  }
}