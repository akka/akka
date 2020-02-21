/*
 * Copyright (C) 2017-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.ActorRef
import akka.persistence.typed.internal.InternalProtocol.ApplyEffect

/**
 * Importing [[FutureEffect]] will extend [[ActorContext]] with the [[effectOnComplete]] method.
 */
object FutureEffect {
  implicit class RichActorContext(val ctx: ActorContext[_]) extends AnyVal {

    /**
     * Will run the supplied `g` method on the result of the `f` future once it has been completed.
     * Any [[Effect]] returned will be applied to the actor contexts `self` actor.
     * The `g` callback will be executed by the execution context provided by `ec`.
     * @param f the future to run g on once completed
     * @param g the onComplete callback to execute
     * @param ec the execution context to execute `g` on
     * @tparam T the type of the result of the future `f`
     * @tparam E the event type of the actor
     * @tparam S the state type of the actor
     * @return the [[Effect]] to apply to the actor
     */
    def effectOnComplete[T, E, S](f: Future[T])(g: Try[T] => S => Effect[E, S])(
        implicit ec: ExecutionContext): ReplyEffect[E, S] = {
      f.onComplete { t =>
        ctx.self.asInstanceOf[ActorRef[Any]] ! ApplyEffect(g(t))
      }
      Effect.noReply
    }
  }
}
