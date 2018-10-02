/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import akka.actor.typed.scaladsl.StashBuffer
import akka.annotation.InternalApi
import akka.persistence.typed.internal.EventsourcedBehavior.InternalProtocol
import akka.util.OptionVal

/**
 * INTERNAL API
 * Main reason for introduction of this trait is stash buffer reference management
 * in order to survive restart of internal behavior
 */
@InternalApi private[akka] trait EventsourcedStashReferenceManagement {

  private var stashBuffer: OptionVal[StashBuffer[InternalProtocol]] = OptionVal.None

  def stashBuffer(settings: EventsourcedSettings): StashBuffer[InternalProtocol] = {
    val buffer: StashBuffer[InternalProtocol] = stashBuffer match {
      case OptionVal.Some(value) ⇒ value
      case _                     ⇒ StashBuffer(settings.stashCapacity)
    }
    this.stashBuffer = OptionVal.Some(buffer)
    stashBuffer.get
  }

  def clearStashBuffer(): Unit = stashBuffer = OptionVal.None
}
