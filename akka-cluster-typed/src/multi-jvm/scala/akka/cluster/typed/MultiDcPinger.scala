/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.typed

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.serialization.jackson.CborSerializable

object MultiDcPinger {

  sealed trait Command extends CborSerializable
  case class Ping(ref: ActorRef[Pong]) extends Command
  case object NoMore extends Command
  case class Pong(dc: String) extends CborSerializable

  def apply(): Behavior[Command] = Behaviors.setup[Command] { ctx =>
    val cluster = Cluster(ctx.system)
    Behaviors.receiveMessage[Command] {
      case Ping(ref) =>
        ref ! Pong(cluster.selfMember.dataCenter)
        Behaviors.same
      case NoMore =>
        Behaviors.stopped
    }
  }
}
