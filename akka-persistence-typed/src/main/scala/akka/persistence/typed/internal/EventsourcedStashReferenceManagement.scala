/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import akka.actor.typed.scaladsl.{ ActorContext, Behaviors, StashBuffer }
import akka.actor.typed.{ PostStop, Signal }
import akka.annotation.InternalApi
import akka.persistence.typed.internal.EventsourcedBehavior.InternalProtocol
import akka.util.OptionVal

/**
 * Main reason for introduction of this trait is stash buffer reference management
 * in order to survive restart of internal behavior
 */
@InternalApi
trait EventsourcedStashReferenceManagement {

  private var stashBuffer: OptionVal[StashBuffer[InternalProtocol]] = OptionVal.None

  def stashBuffer(settings: EventsourcedSettings): StashBuffer[InternalProtocol] = {
    val buffer: StashBuffer[InternalProtocol] = stashBuffer match {
      case OptionVal.Some(value) ⇒ value
      case _                     ⇒ StashBuffer(settings.stashCapacity)
    }
    this.stashBuffer = OptionVal.Some(buffer)
    stashBuffer.get
  }

  def onSignalCleanup: (ActorContext[InternalProtocol], Signal) ⇒ Unit = {
    case (ctx, PostStop) ⇒
      stashBuffer match {
        case OptionVal.Some(buffer) ⇒ buffer.unstashAll(ctx, Behaviors.ignore)
        case _                      ⇒ Unit
      }
    case _ ⇒ Unit
  }
}
