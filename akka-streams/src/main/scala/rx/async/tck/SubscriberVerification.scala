package rx.async.tck

import org.testng.annotations.Test
import rx.async.spi.Publisher

abstract class SubscriberVerification[T] extends TestEnvironment {
  import TestEnvironment._

  // TODO: make the timeouts be dilate-able so that one can tune the suite for the machine it runs on

  /**
   * This is the main method you must implement in your test incarnation.
   * It must create a Publisher for a stream with exactly the given number of elements.
   * If `elements` is zero the produced stream must be infinite.
   */
  def createSubscriber(elements: Int): Publisher[T]

  ////////////////////// SPEC RULE VERIFICATION ///////////////////////////

  // Subscriber::onSubscribe(Subscription), Subscriber::onNext(T)
  //   must asynchronously schedule a respective event to the subscriber
  //   must not call any methods on the Subscription, the Publisher or any other Publishers or Subscribers
  @Test
  def onSubscribeAndOnNextMustAsynchronouslyScheduleAnEvent(): Unit = {
    // TODO
  }

  // Subscriber::onComplete, Subscriber::onError(Throwable)
  //   must asynchronously schedule a respective event to the Subscriber
  //   must not call any methods on the Subscription, the Publisher or any other Publishers or Subscribers
  //   must consider the Subscription cancelled after having received the event
  @Test
  def onCompleteAndOnErrorMustAsynchronouslyScheduleAnEvent(): Unit = {
    // TODO
  }

  // A Subscriber
  //   must not accept an `onSubscribe` event if it already has an active Subscription
  @Test
  def mustNotAcceptAnOnSubscribeEventIfItAlreadyHasAnActiveSubscription(): Unit = {
    // TODO
  }

  // A Subscriber
  //   must call Subscription::cancel during shutdown if it still has an active Subscription
  @Test
  def mustCallSubscriptionCancelDuringShutdownIfItStillHasAnActiveSubscription(): Unit = {
    // TODO
  }

  // A Subscriber
  //   must ensure that all calls on a Subscription take place from the same thread or provide for respective external synchronization
  @Test
  def mustEnsureThatAllCallsOnASubscriptionTakePlaceFromTheSameThreadOrProvideExternalSync(): Unit = {
    // TODO
  }

  // A Subscriber
  //   must be prepared to receive one or more `onNext` events after having called Subscription::cancel
  @Test
  def mustBePreparedToReceiveOneOrMoreOnNextEventsAfterHavingCalledSubscriptionCancel(): Unit = {
    // TODO
  }

  // A Subscriber
  //   must be prepared to receive an `onComplete` event with or without a preceding Subscription::requestMore call
  @Test
  def mustBePreparedToReceiveAnOnCompleteEventWithOrWithoutAPrecedingSubscriptionRequestMore(): Unit = {
    // TODO
  }

  // A Subscriber
  //   must be prepared to receive an `onError` event with or without a preceding Subscription::requestMore call
  @Test
  def mustBePreparedToReceiveAnOnErrorEventWithOrWithoutAPrecedingSubscriptionRequestMoreCall(): Unit = {
    // TODO
  }

  // A Subscriber
  //   must make sure that all calls on its `onXXX` methods happen-before the processing of the respective events
  @Test
  def mustMakeSureThatAllCallsOnItsMethodsHappenBeforeTheProcessingOfTheRespectiveEvents(): Unit = {
    // TODO
  }

  /////////////////////// ADDITIONAL "COROLLARY" TESTS //////////////////////

  /////////////////////// TEST INFRASTRUCTURE //////////////////////

}