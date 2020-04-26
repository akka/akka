/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import akka.actor.typed.ActorRef

trait IdempotentCommand {
  val idempotencyKey: String
  val replyTo: ActorRef[IdempotenceReply]

  val writeConfig: IdempotenceKeyWriteConfig = AlwaysWriteIdempotenceKey
}

trait IdempotenceReply
case object IdempotenceFailure extends IdempotenceReply

sealed trait IdempotenceKeyWriteConfig {
  def doExplicitWrite(persistEffectPresent: Boolean): Boolean
}
case object AlwaysWriteIdempotenceKey extends IdempotenceKeyWriteConfig {
  override def doExplicitWrite(persistEffectPresent: Boolean): Boolean = !persistEffectPresent
}
case object OnlyWriteIdempotenceKeyWithPersist extends IdempotenceKeyWriteConfig {
  override def doExplicitWrite(persistEffectPresent: Boolean): Boolean = false
}
