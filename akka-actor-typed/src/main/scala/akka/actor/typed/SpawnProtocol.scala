/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import akka.actor.typed.scaladsl.Behaviors

object SpawnProtocol {

  object Spawn {
    /**
     * Special factory to make using Spawn with ask easier
     */
    def apply[T](behavior: Behavior[T], name: String, props: Props): ActorRef[ActorRef[T]] ⇒ Spawn[T] =
      replyTo ⇒ new Spawn(behavior, name, props, replyTo)
  }

  final case class Spawn[T](behavior: Behavior[T], name: String, props: Props, replyTo: ActorRef[ActorRef[T]])
    extends SpawnProtocol {

  }

  val behavior: Behavior[SpawnProtocol] =
    Behaviors.receive { (ctx, msg) ⇒
      msg match {
        case Spawn(bhvr, name, props, replyTo) ⇒
          val ref =
            if (name == null || name.equals(""))
              ctx.spawnAnonymous(bhvr, props)
            else
              ctx.spawn(bhvr, name, props)
          replyTo ! ref
          Behaviors.same
      }
    }

}

sealed abstract class SpawnProtocol
