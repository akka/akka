/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.cluster

import akka.cluster.sharding.ClusterShardingSettings
import akka.typed.scaladsl.Actor
import akka.typed.scaladsl.adapter._
import akka.typed.{ ActorSystem }

class ClusterShardingApiSpec {

  // Compile only for now

  val system: akka.actor.ActorSystem = ???
  val typedSystem: ActorSystem[Nothing] = system.toTyped
  val cluster = Cluster(typedSystem)

  trait EntityProtocol
  case class Add(thing: String) extends EntityProtocol
  case object PassHence extends EntityProtocol

  val entityBehavior =
    Actor.deferred[EntityProtocol] { _ ⇒
      var things: List[String] = Nil

      Actor.immutable[EntityProtocol] { (_, msg) ⇒
        msg match {
          case Add(thing) ⇒
            things = thing :: things
            Actor.same

          case PassHence ⇒
            Actor.stopped
        }
      }
    }

  val sharding = ClusterSharding(typedSystem).spawn(
    entityBehavior,
    "things-lists",
    ClusterShardingSettings(typedSystem.settings.config),
    maxNumberOfShards = 25,
    handOffStopMessage = PassHence
  )

  sharding ! ShardingEnvelope("1", Add("bananas"))

  val entity1 = ClusterSharding.entityRefFor("1", sharding)
  entity1 ! Add("pineapple")

  // start but no command
  sharding ! StartEntity("2")

}
