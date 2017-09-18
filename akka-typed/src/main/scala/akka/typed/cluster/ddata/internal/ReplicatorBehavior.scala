/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.cluster.ddata.internal

import akka.typed.cluster.ddata.scaladsl.Replicator
import akka.typed.cluster.ddata.scaladsl.ReplicatorSettings
import akka.annotation.InternalApi
import akka.typed.Behavior
import akka.typed.scaladsl.Actor
import akka.typed.scaladsl.adapter._
import akka.cluster.{ ddata ⇒ dd }

/**
 * INTERNAL API
 */
@InternalApi private[akka] object ReplicatorBehavior {
  import Replicator._
  def behavior(settings: ReplicatorSettings): Behavior[Command[_]] = {
    val untypedReplicatorProps = dd.Replicator.props(settings)

    Actor.deferred { ctx ⇒
      // FIXME perhaps add supervisor for restarting
      val untypedReplicator = ctx.actorOf(untypedReplicatorProps, name = "underlying")

      Actor.immutable[Command[_]] { (ctx, msg) ⇒
        msg match {
          case cmd: Replicator.Get[_] ⇒
            untypedReplicator.tell(
              dd.Replicator.Get(cmd.key, cmd.consistency, cmd.request),
              sender = cmd.replyTo.toUntyped)
            Actor.same

          case cmd: Replicator.Update[_] ⇒
            untypedReplicator.tell(
              dd.Replicator.Update(cmd.key, cmd.writeConsistency, cmd.request)(cmd.modify),
              sender = cmd.replyTo.toUntyped)
            Actor.same
        }
      }

    }
  }
}
