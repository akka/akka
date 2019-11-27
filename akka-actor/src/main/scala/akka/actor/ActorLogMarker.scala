/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor

import akka.annotation.ApiMayChange
import akka.event.LogMarker

@ApiMayChange
object ActorLogMarker {

  /**
   * Marker "akkaDeadLetter" of log event for dead letter messages.
   *
   * @param messageClass The message class of the DeadLetter. Included as property "akkaMessageClass".
   */
  def deadLetter(messageClass: String): LogMarker =
    LogMarker("akkaDeadLetter", Map(LogMarker.Properties.MessageClass -> messageClass))

}
