/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import scala.reflect.ClassTag

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.internal.BehaviorImpl
import akka.annotation.InternalApi
import akka.persistence.typed.scaladsl.CommandConfirmation
import akka.util.ConstantFun

/**
 * INTERNAL API
 */
@InternalApi private[akka] final case class CommandWithAutoConfirmation[+C](command: C, replyTo: ActorRef[CommandConfirmation])

/**
 * INTERNAL API
 */
@InternalApi private[akka] object CommandWithAutoConfirmation {
  def autoReplyAfter[T](behavior: Behavior[T]): Behavior[T] = {
    BehaviorImpl.intercept[T, CommandWithAutoConfirmation[T]](
      beforeMessage = (_, msg) ⇒ {
        msg.command
      },
      beforeSignal = ConstantFun.scalaAnyTwoToTrue,
      afterMessage = (_, msg, b) ⇒ {
        println(s"# AUTO reply to $msg, behavior ${b.getClass}") // FIXME
        msg.replyTo ! CommandConfirmation.Success
        b
      },
      afterSignal = ConstantFun.scalaAnyThreeToThird,
      behavior)(ClassTag(classOf[CommandWithAutoConfirmation[T]]))
  }
}
