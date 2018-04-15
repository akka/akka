/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

object CommandConfirmation {
  trait Error
  final case class Invalid(message: String) extends Error
  case object PersistFailed extends Error

  def error(err: Error): CommandConfirmation =
    CommandConfirmation(error = Some(err))

  val Success: CommandConfirmation = CommandConfirmation(error = None)
}

final case class CommandConfirmation(error: Option[CommandConfirmation.Error])
