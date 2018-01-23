/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor.typed.internal

import akka.actor.typed.scaladsl.MutableStashBuffer
import akka.actor.typed.{Behavior, scaladsl}
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

  def delayStart[T](delayedBehavior: Behavior[T]): Behavior[Any] =
    scaladsl.Behaviors.deferred[Any] { _ ⇒
      val buffer = MutableStashBuffer[Any](1000)

      scaladsl.Behaviors.immutable[Any] { (sctx, msg) ⇒
        // delay actual initialization until the actor system is started as the actor may touch
        // other parts of the actor system which would not be ready unless delayed
        msg match {
          case Start ⇒
            buffer.unstashAll(sctx, delayedBehavior.asInstanceOf[Behavior[Any]])
          case t ⇒
            if (!buffer.isFull) buffer.stash(t)
            Behavior.same
        }
      }.onSignal {
        case (_, signal) ⇒
          buffer.stash(signal)
          Behavior.same
      }
    }

}
