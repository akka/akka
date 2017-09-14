/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.cluster

import akka.actor.Address
import akka.cluster.ClusterEvent.{ MemberEvent, MemberUp }
import akka.cluster.sharding.ClusterShardingSettings
import akka.cluster.sharding.ShardCoordinator.LeastShardAllocationStrategy
import akka.typed.cluster.Cluster._
import akka.typed.cluster.ClusterSharding.TypedMessageExtractor
import akka.typed.scaladsl.Actor
import akka.typed.scaladsl.adapter._
import akka.typed.{ ActorSystem, Behavior, Props }

class ClusterShardingApiSpec {

  // Compile only for now

  val system: akka.actor.ActorSystem = ???
  val typedSystem: ActorSystem[Nothing] = system.toTyped
  val cluster = Cluster(typedSystem)

  case class Envelope(id: String, msg: EntityProtocol)

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
    "tings-lists",
    ClusterShardingSettings(typedSystem.settings.config),
    // TODO convenience thingie here or factory/dsl something perhaps
    new TypedMessageExtractor[Envelope, EntityProtocol] {
      def entityId(message: Envelope) = message.id
      def entityMessage(message: Envelope) = message.msg
      def shardId(message: Envelope) = (math.abs(message.id.hashCode) % 10).toString
    },
    PassHence
  )

  sharding ! Envelope("1", Add("bananas"))

}
