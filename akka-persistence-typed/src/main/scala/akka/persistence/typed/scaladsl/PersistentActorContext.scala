/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import akka.actor.typed.internal.adapter.ActorContextAdapter
import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.internal.Running

object PersistentActorContext {

  /**
   * Access the to details about the persistent actor from the actor context, can only be used in response
   * to a command, will throw `IllegalState` for other cases
   */
  def apply(context: ActorContext[_]): PersistentActorContext = {
    context match {
      case impl: ActorContextAdapter[_] ⇒
        impl.currentBehavior match {
          case w: Running.WithStateAccessible ⇒
            new PersistentActorContext {
              def lastSequenceNr: Long = w.state.seqNr
            }

          case s ⇒ throw new IllegalStateException(s"Cannot extract the PersistentActorContext in state ${s.getClass.getName}")

        }

      case c ⇒ throw new IllegalStateException(s"Cannot extract the PersistentActorContext from context ${c.getClass.getName}")
    }
  }

}

trait PersistentActorContext {
  def lastSequenceNr: Long
}

