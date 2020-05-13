/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import akka.actor.typed.ActorRef

trait IdempotentCommand[T, S] {
  val idempotencyKey: String
  val replyTo: ActorRef[IdempotenceReply[T, S]]

  val writeConfig: IdempotenceKeyWriteConfig = AlwaysWriteIdempotenceKey
}

sealed trait IdempotenceReply[T, S]
case class IdempotenceSuccess[T, S](data: T) extends IdempotenceReply[T, S]
case class IdempotenceFailure[T, S](state: S) extends IdempotenceReply[T, S]

sealed trait IdempotenceKeyWriteConfig {
  def doExplicitWrite(persistEffectPresent: Boolean): Boolean
}
case object AlwaysWriteIdempotenceKey extends IdempotenceKeyWriteConfig {
  override def doExplicitWrite(persistEffectPresent: Boolean): Boolean = !persistEffectPresent
}
case object OnlyWriteIdempotenceKeyWithPersist extends IdempotenceKeyWriteConfig {
  override def doExplicitWrite(persistEffectPresent: Boolean): Boolean = false
}
