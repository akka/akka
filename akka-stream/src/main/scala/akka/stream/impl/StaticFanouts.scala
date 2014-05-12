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

  override val primaryOutputs = new FanoutOutputs(settings.maxFanOutBufferSize, settings.initialFanOutBufferSize, self, pump = this) {

    var hasOtherSubscription = false
    var hasDownstreamSubscription = false
    var pendingRemoveSubscription: List[S] = Nil

    registerSubscriber(other.getSubscriber)

    override def registerSubscriber(subscriber: Subscriber[Any]): Unit = {
      super.registerSubscriber(subscriber)
      if (subscriber == other.getSubscriber)
        hasOtherSubscription = true
      else
        hasDownstreamSubscription = true
      if (pendingRemoveSubscription.nonEmpty && hasOtherSubscription && hasDownstreamSubscription) {
        pendingRemoveSubscription foreach unregisterSubscription
        pendingRemoveSubscription = Nil
      }
    }

    override def unregisterSubscription(subscription: S): Unit = {
      // make sure that we don't shutdown because of premature cancel
      if (hasOtherSubscription && hasDownstreamSubscription)
        super.unregisterSubscription(subscription)
      else
        pendingRemoveSubscription :+= subscription // defer these until both subscriptions have been registered
    }

    override def afterShutdown(): Unit = {
      primaryOutputsShutdown = true
      shutdownHooks()
    }
  }

  var running = TransferPhase(primaryInputs.NeedsInput && primaryOutputs.NeedsDemand) { () ⇒
    val in = primaryInputs.dequeueInputElement()
    primaryOutputs.enqueueOutputElement(in)
  }

  nextPhase(running)

}

