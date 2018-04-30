/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import akka.actor.typed.ActorRef
import akka.annotation.InternalApi
import akka.persistence.typed.scaladsl.CommandConfirmation

/**
 * INTERNAL API
 */
@InternalApi private[akka] final case class CommandWithAutoConfirmation[+C](command: C, replyTo: ActorRef[CommandConfirmation])
