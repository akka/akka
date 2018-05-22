/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.javadsl

import akka.actor.typed.javadsl.ActorContext
import akka.annotation.{ ApiMayChange, DoNotInherit }
import akka.persistence.typed.internal

@DoNotInherit
@ApiMayChange
trait PersistentActorContext[T] extends internal.PersistentActorContext[T] with ActorContext[T] {

  /**
   * Get the `scaladsl` of this `PersistentActorContext`.
   *
   * This method is thread-safe and can be called from other threads than the ordinary
   * actor message processing thread, such as [[java.util.concurrent.CompletionStage]] callbacks.
   */
  def asScala: akka.persistence.typed.scaladsl.PersistentActorContext[T]

  /**
   * Highest received sequence number so far or `0L` if the actor hasn't replayed
   * or stored any persistent events yet.
   */
  def getLastSequenceNr: Long
}
