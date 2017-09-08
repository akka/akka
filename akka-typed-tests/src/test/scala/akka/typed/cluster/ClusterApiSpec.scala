/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.cluster

import akka.actor.Address
import akka.cluster.ClusterEvent.{MemberEvent, MemberUp}
import akka.typed.ActorSystem
import akka.typed.scaladsl.Actor
import akka.typed.cluster.Cluster._
import akka.typed.scaladsl.adapter._

class ClusterApiSpec {

  // Compile only for now

  val system: akka.actor.ActorSystem = ???
  val typedSystem: ActorSystem[Nothing] = system.toTyped
  val cluster = Cluster(typedSystem)

  val clusterSubscriber = Actor.deferred[MemberEvent] { ctx =>

    cluster.subscriptions ! Subscribe[MemberEvent](ctx.self)

    Actor.immutable[MemberEvent] { (_, msg) =>
      msg match {
        case up: MemberUp if up.member.address == cluster.self.address=>
          cluster.manager ! Leave(cluster.self.address)
          Actor.same

        case other =>
          println(s"Got cluster state event $other")
          Actor.same
      }
    }
  }

  system.spawn(clusterSubscriber, "cluster-subscriber")

  cluster.manager ! Join(cluster.self.address)

  val n1: Address = ???
  val n2: Address = ???
  val n3: Address = ???

  cluster.manager ! JoinSeedNodes(List(cluster.self.address, n1, n2, n3))

}
