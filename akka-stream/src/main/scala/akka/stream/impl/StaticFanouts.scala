/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.stream.MaterializerSettings
import org.reactivestreams.{ Subscriber, Subscription, Publisher }

/**
 * INTERNAL API
 */
private[akka] class TeeImpl(_settings: MaterializerSettings, other: Subscriber[Any])
  extends ActorProcessorImpl(_settings) {

  override val primaryOutputs = new FanoutOutputs(settings.maxFanOutBufferSize, settings.initialFanOutBufferSize, self, pump = this) {
    var secondarySubscribed = false

    override def registerSubscriber(subscriber: Subscriber[Any]): Unit = {
      if (!secondarySubscribed) {
        super.registerSubscriber(other)
        secondarySubscribed = true
      }
      super.registerSubscriber(subscriber)
    }

    override def afterShutdown(): Unit = {
      primaryOutputsShutdown = true
      shutdownHooks()
    }
  }

  val running = TransferPhase(primaryInputs.NeedsInput && primaryOutputs.NeedsDemand) { () ⇒
    val in = primaryInputs.dequeueInputElement()
    primaryOutputs.enqueueOutputElement(in)
  }

  nextPhase(running)

}

