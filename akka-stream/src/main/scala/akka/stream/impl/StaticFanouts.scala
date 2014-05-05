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
    exposedPublisher.subscribe(other.getSubscriber)
    super.primaryOutputsReady()
  }

  override val primaryOutputs = new FanoutOutputs(settings.maxFanOutBufferSize, settings.initialFanOutBufferSize) {

    var otherSubscription: Option[S] = None
    var otherRequestedOrCanceled = false
    var pendingRemoveSubscription: List[S] = Nil

    def isOtherSubscription(subscription: Subscription): Boolean = otherSubscription.exists(_ == subscription)

    override type S = ActorSubscription[Any]
    override def createSubscription(subscriber: Subscriber[Any]): S = {
      val s = new ActorSubscription(self, subscriber)
      if (subscriber == other.getSubscriber) {
        otherSubscription = Some(s)
        pendingRemoveSubscription foreach removeSubscription
        pendingRemoveSubscription = Nil
      }
      s
    }
    override def afterShutdown(completed: Boolean): Unit = {
      primaryOutputsFinished(completed)
    }

    override val NeedsDemand: TransferState = new TransferState {
      def isReady = demandAvailable && otherRequestedOrCanceled
      def isCompleted = isClosed
    }

    override def handleRequest(subscription: S, elements: Int): Unit = {
      if (otherRequestedOrCanceled || isOtherSubscription(subscription))
        otherRequestedOrCanceled = true
      super.handleRequest(subscription, elements)
    }

    override def removeSubscription(subscription: S): Unit = {
      // make sure that we don't shutdown because of early cancel from downstream
      if (otherSubscription.isEmpty && !isOtherSubscription(subscription))
        pendingRemoveSubscription :+= subscription // defer these until other subscription comes in
      otherRequestedOrCanceled = true
      super.removeSubscription(subscription)
    }

  }

  override def transfer(): TransferState = {
    val in = primaryInputs.dequeueInputElement()
    primaryOutputs.enqueueOutputElement(in)
    needsBothInputAndDemand
  }
}

