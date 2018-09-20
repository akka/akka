/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed
package internal
package adapter

import scala.concurrent.{ Future, Promise }
import scala.util.Success
import scala.util.control.NonFatal

import akka.actor.setup.Setup
import akka.actor.typed.scaladsl.Behaviors
import akka.annotation.InternalApi

/**
 * Wrap a behavior and complete a promise when that behavior stops or terminates.
 * This is used to wrap the guardian actor and implement the `ActorSystem.whenTerminated`.
 */
@InternalApi private[akka] object WhenTerminated {
  def apply[T](currentBehavior: Behavior[T], completionPromise: Promise[Terminated]): Behavior[T] = {
    Behaviors.setup[T] { ctx ⇒
      withCompletion(completionPromise, ctx) {
        val started = Behavior.validateAsInitial(Behavior.start(currentBehavior, ctx))
        if (Behavior.isAlive(started)) new WrappedBehavior(started, completionPromise)
        else started
      }
    }
  }

  private def withCompletion[T](completionPromise: Promise[Terminated], ctx: ActorContext[T])(calculateBehavior: ⇒ Behavior[T]): Behavior[T] = {
    try {
      val behavior = calculateBehavior
      if (!Behavior.isAlive(behavior) && !completionPromise.isCompleted) {
        completionPromise.complete(Success(Terminated(ctx.asScala.self)(null)))
      }
      behavior
    } catch {
      case NonFatal(t) if !completionPromise.isCompleted ⇒
        completionPromise.complete(Success(Terminated(ctx.asScala.self)(t)))
        throw t
    }
  }

  /**
   * Wrap a behavior and complete a promise when that behavior stops or terminates.
   *
   * @param currentBehavior
   * @param completionPromise
   * @tparam T
   */
  final private class WrappedBehavior[T](currentBehavior: Behavior[T], completionPromise: Promise[Terminated]) extends ExtensibleBehavior[T] {
    override def receive(ctx: ActorContext[T], msg: T): Behavior[T] = {
      withCompletion(completionPromise, ctx) {
        wrap(Behavior.interpretMessage(currentBehavior, ctx, msg), ctx)
      }
    }

    override def receiveSignal(ctx: ActorContext[T], msg: Signal): Behavior[T] = {
      // This is probably not required (advise??)
      if (msg == PostStop && !completionPromise.isCompleted) {
        completionPromise.complete(Success(Terminated(ctx.asScala.self)(null)))
      }

      withCompletion(completionPromise, ctx) {
        wrap(Behavior.interpretSignal(currentBehavior, ctx, msg), ctx)
      }
    }

    private def wrap(next: Behavior[T], ctx: ActorContext[T]): Behavior[T] = {
      Behavior.wrap(this, next, ctx) {
        WhenTerminated(_, completionPromise)
      }
    }
  }
}

@InternalApi private[akka] final case class WhenTerminatedSetup(whenGuardianTerminated: Future[Terminated]) extends Setup
