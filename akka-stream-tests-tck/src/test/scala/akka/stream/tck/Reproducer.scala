/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.tck

import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Flow
import org.junit.Test
import org.reactivestreams.{ Processor, Publisher, Subscriber }
import org.reactivestreams.tck.SubscriberWhiteboxVerification
import org.scalatest.WordSpecLike

class ReproducerTest extends AkkaIdentityProcessorVerification[Int] {

  override def required_spec109_mustIssueOnSubscribeForNonNullSubscriber(): Unit = {
    println("Reproducer")
    var i = 0
    while (i < 100000) {
      println("Run " + i)
      super.required_spec109_mustIssueOnSubscribeForNonNullSubscriber()
      i += 1
    }
  }

  override def createIdentityProcessor(maxBufferSize: Int): Processor[Int, Int] = {
    implicit val materializer = ActorMaterializer()(system)
    Flow[Int].toProcessor.run()
  }

  override def createElement(element: Int): Int = element
  /*
  override def optional_spec111_maySupportMultiSubscribe(): Unit = ()
  override def untested_spec315_cancelMustNotThrowExceptionAndMustSignalOnError(): Unit = ()
  override def required_spec210_mustBePreparedToReceiveAnOnErrorSignalWithoutPrecedingRequestCall(): Unit = ()
  override def optional_spec111_multicast_mustProduceTheSameElementsInTheSameSequenceToAllOfItsSubscribersWhenRequestingOneByOne(): Unit = ()
  override def untested_spec202_shouldAsynchronouslyDispatch(): Unit = ()
  override def untested_spec107_mustNotEmitFurtherSignalsOnceOnErrorHasBeenSignalled(): Unit = ()
  override def required_exerciseWhiteboxHappyPath(): Unit = ()
  // override def required_spec109_mustIssueOnSubscribeForNonNullSubscriber(): Unit = ()
  override def untested_spec311_requestMaySynchronouslyCallOnCompleteOrOnError(): Unit = ()
  override def untested_spec314_cancelMayCauseThePublisherToShutdownIfNoOtherSubscriptionExists(): Unit = ()
  override def required_spec312_cancelMustMakeThePublisherToEventuallyStopSignaling(): Unit = ()
  override def required_spec203_mustNotCallMethodsOnSubscriptionOrPublisherInOnComplete(): Unit = ()
  override def required_spec109_mayRejectCallsToSubscribeIfPublisherIsUnableOrUnwillingToServeThemRejectionMustTriggerOnErrorAfterOnSubscribe(): Unit = ()
  override def required_spec308_requestMustRegisterGivenNumberElementsToBeProduced(): Unit = ()
  override def optional_spec104_mustSignalOnErrorWhenFails(): Unit = ()
  override def required_spec213_onError_mustThrowNullPointerExceptionWhenParametersAreNull(): Unit = ()
  override def untested_spec204_mustConsiderTheSubscriptionAsCancelledInAfterRecievingOnCompleteOrOnError(): Unit = ()
  override def untested_spec108_possiblyCanceledSubscriptionShouldNotReceiveOnErrorOrOnCompleteSignals(): Unit = ()
  override def required_spec210_mustBePreparedToReceiveAnOnErrorSignalWithPrecedingRequestCall(): Unit = ()
  override def untested_spec211_mustMakeSureThatAllCallsOnItsMethodsHappenBeforeTheProcessingOfTheRespectiveEvents(): Unit = ()
  override def required_spec317_mustSupportAPendingElementCountUpToLongMaxValue(): Unit = ()
  override def required_spec307_afterSubscriptionIsCancelledAdditionalCancelationsMustBeNops(): Unit = ()
  override def untested_spec310_requestMaySynchronouslyCallOnNextOnSubscriber(): Unit = ()
  override def optional_spec309_requestNegativeNumberMaySignalIllegalArgumentExceptionWithSpecificMessage(): Unit = ()
  override def untested_spec212_mustNotCallOnSubscribeMoreThanOnceBasedOnObjectEquality_specViolation(): Unit = ()
  override def stochastic_spec103_mustSignalOnMethodsSequentially(): Unit = ()
  override def required_spec104_mustCallOnErrorOnAllItsSubscribersIfItEncountersANonRecoverableError(): Unit = ()
  override def required_spec205_mustCallSubscriptionCancelIfItAlreadyHasAnSubscriptionAndReceivesAnotherOnSubscribeSignal(): Unit = ()
  override def required_spec302_mustAllowSynchronousRequestCallsFromOnNextAndOnSubscribe(): Unit = ()
  override def required_spec213_onNext_mustThrowNullPointerExceptionWhenParametersAreNull(): Unit = ()
  override def untested_spec316_requestMustNotThrowExceptionAndMustOnErrorTheSubscriber(): Unit = ()
  override def required_validate_maxElementsFromPublisher(): Unit = ()
  override def untested_spec206_mustCallSubscriptionCancelIfItIsNoLongerValid(): Unit = ()
  override def required_spec209_mustBePreparedToReceiveAnOnCompleteSignalWithoutPrecedingRequestCall(): Unit = ()
  override def required_spec317_mustSupportACumulativePendingElementCountUpToLongMaxValue(): Unit = ()
  override def required_spec313_cancelMustMakeThePublisherEventuallyDropAllReferencesToTheSubscriber(): Unit = ()
  override def untested_spec110_rejectASubscriptionRequestIfTheSameSubscriberSubscribesTwice(): Unit = ()
  override def required_validate_boundedDepthOfOnNextAndRequestRecursion(): Unit = ()
  override def optional_spec111_multicast_mustProduceTheSameElementsInTheSameSequenceToAllOfItsSubscribersWhenRequestingManyUpfront(): Unit = ()
  override def optional_spec105_emptyStreamMustTerminateBySignallingOnComplete(): Unit = ()
  override def required_spec309_requestNegativeNumberMustSignalIllegalArgumentException(): Unit = ()
  override def required_mustRequestFromUpstreamForElementsThatHaveBeenRequestedLongAgo(): Unit = ()
  override def untested_spec106_mustConsiderSubscriptionCancelledAfterOnErrorOrOnCompleteHasBeenCalled(): Unit = ()
  override def required_spec208_mustBePreparedToReceiveOnNextSignalsAfterHavingCalledSubscriptionCancel(): Unit = ()
  override def optional_spec111_multicast_mustProduceTheSameElementsInTheSameSequenceToAllOfItsSubscribersWhenRequestingManyUpfrontAndCompleteAsExpected(): Unit = ()
  override def required_createPublisher3MustProduceAStreamOfExactly3Elements(): Unit = ()
  override def untested_spec213_failingOnSignalInvocation(): Unit = ()
  override def required_spec101_subscriptionRequestMustResultInTheCorrectNumberOfProducedElements(): Unit = ()
  override def required_spec213_onSubscribe_mustThrowNullPointerExceptionWhenParametersAreNull(): Unit = ()
  override def untested_spec305_cancelMustNotSynchronouslyPerformHeavyComputation(): Unit = ()
  override def required_spec303_mustNotAllowUnboundedRecursion(): Unit = ()
  override def required_spec209_mustBePreparedToReceiveAnOnCompleteSignalWithPrecedingRequestCall(): Unit = ()
  override def untested_spec301_mustNotBeCalledOutsideSubscriberContext(): Unit = ()
  override def untested_spec304_requestShouldNotPerformHeavyComputations(): Unit = ()
  override def required_spec109_subscribeThrowNPEOnNullSubscriber(): Unit = ()
  override def required_spec105_mustSignalOnCompleteWhenFiniteStreamTerminates(): Unit = ()
  override def untested_spec207_mustEnsureAllCallsOnItsSubscriptionTakePlaceFromTheSameThreadOrTakeCareOfSynchronization(): Unit = ()
  override def required_spec317_mustNotSignalOnErrorWhenPendingAboveLongMaxValue(): Unit = ()
  override def required_spec201_mustSignalDemandViaSubscriptionRequest(): Unit = ()
  override def optional_spec111_registeredSubscribersMustReceiveOnNextOrOnCompleteSignals(): Unit = ()
  override def required_spec309_requestZeroMustSignalIllegalArgumentException(): Unit = ()
  override def required_spec107_mustNotEmitFurtherSignalsOnceOnCompleteHasBeenSignalled(): Unit = ()
  override def required_spec203_mustNotCallMethodsOnSubscriptionOrPublisherInOnError(): Unit = ()
  override def required_spec306_afterSubscriptionIsCancelledRequestMustBeNops(): Unit = ()
  override def required_spec102_maySignalLessThanRequestedAndTerminateSubscription(): Unit = ()
  */
}
