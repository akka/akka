/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor.typed.internal

import akka.actor.typed.{ ActorContext, Behavior, Signal, scaladsl }
import akka.annotation.InternalApi

/**
 * INTERNAL API
 *
 * A behavior that wraps another behavior, stashing all incoming messages and signals until
 * it is sent the `Start` message, it then unstashes all messages and signals and switches to the delayedBehavior
 */
@InternalApi
private[akka] object DelayedStart {

  case object Start

  // note that we cannot use StashBuffer here as we need to buffer signals as well as messages
  def delayStart[T](delayedBehavior: Behavior[T], buffer: List[Any] = Nil): Behavior[Any] =
    scaladsl.Behaviors.immutable[Any] { (sctx, msg) ⇒
      // delay actual initialization until the actor system is started as the actor may touch
      // other parts of the actor system which would not be ready unless delayed
      val ctx = sctx.asInstanceOf[ActorContext[T]]
      msg match {
        case Start ⇒
          val endState = buffer.reverseIterator.foldLeft(Behavior.undefer(delayedBehavior, ctx)) { (behavior, buffered) ⇒
            if (Behavior.isAlive(behavior)) {
              // delayed application of signals and messages in the order they arrived
              val newBehavior = buffered match {
                case s: Signal ⇒
                  Behavior.interpretSignal(behavior, ctx.asInstanceOf[ActorContext[T]], s)
                case other ⇒
                  Behavior.interpretMessage(behavior, ctx, other.asInstanceOf[T])
              }
              Behavior.canonicalize(newBehavior, behavior, ctx)
            } else behavior
          }
          endState.asInstanceOf[Behavior[Any]]
        case t ⇒
          delayStart(delayedBehavior, t :: buffer)
      }
    }.onSignal {
      case (_, signal) ⇒
        delayStart(delayedBehavior, signal :: buffer)
    }

}
