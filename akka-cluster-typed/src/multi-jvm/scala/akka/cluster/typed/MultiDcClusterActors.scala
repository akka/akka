/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.typed

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors

object MultiDcClusterActors {
  case class Pong(dc: String)
  sealed trait PingProtocol
  case class Ping(ref: ActorRef[Pong]) extends PingProtocol
  case object NoMore extends PingProtocol

  val multiDcPinger = Behaviors.setup[PingProtocol] { ctx =>
    val cluster = Cluster(ctx.system)
    Behaviors.receiveMessage[PingProtocol] {
      case Ping(ref) =>
        ref ! Pong(cluster.selfMember.dataCenter)
        Behaviors.same
      case NoMore =>
        Behaviors.stopped
    }
  }
}
