/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import org.reactivestreams.api.Producer
import akka.stream.MaterializerSettings
import org.reactivestreams.api.Consumer
import org.reactivestreams.spi.Subscriber
import org.reactivestreams.spi.Subscription

/**
 * INTERNAL API
 */
private[akka] class TeeImpl(_settings: MaterializerSettings, other: Consumer[Any])
  extends ActorProcessorImpl(_settings) {

  lazy val needsBothInputAndDemand = primaryInputs.NeedsInput && primaryOutputs.NeedsDemand

  override def initialTransferState = needsBothInputAndDemand

  override def primaryOutputsReady(): Unit = {
    primaryOutputs.addSubscriber(other.getSubscriber)
    super.primaryOutputsReady()
  }

  override val primaryOutputs = new FanoutOutputs(settings.maxFanOutBufferSize, settings.initialFanOutBufferSize) {

    var hasOtherSubscription = false
    var hasDownstreamSubscription = false
    var pendingRemoveSubscription: List[S] = Nil

    override type S = ActorSubscription[Any]
    override def createSubscription(subscriber: Subscriber[Any]): S =
      new ActorSubscription(self, subscriber)
    override def afterShutdown(completed: Boolean): Unit = {
      primaryOutputsFinished(completed)
    }

    override val NeedsDemand: TransferState = new TransferState {
      def isReady = demandAvailable
      def isCompleted = isClosed
    }

    override def addSubscriber(subscriber: Subscriber[Any]): Unit = {
      super.addSubscriber(subscriber)
      if (subscriber == other.getSubscriber)
        hasOtherSubscription = true
      else
        hasDownstreamSubscription = true
      if (pendingRemoveSubscription.nonEmpty && hasOtherSubscription && hasDownstreamSubscription) {
        pendingRemoveSubscription foreach removeSubscription
        pendingRemoveSubscription = Nil
      }
    }

    override def removeSubscription(subscription: S): Unit = {
      // make sure that we don't shutdown because of premature cancel
      if (hasOtherSubscription && hasDownstreamSubscription)
        super.removeSubscription(subscription)
      else
        pendingRemoveSubscription :+= subscription // defer these until both subscriptions have been registered
    }
  }

  override def transfer(): TransferState = {
    val in = primaryInputs.dequeueInputElement()
    primaryOutputs.enqueueOutputElement(in)
    needsBothInputAndDemand
  }
}

