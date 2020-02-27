/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed

import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.Behavior
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.Routers
import akka.cluster.MultiNodeClusterSpec
import akka.cluster.sharding.typed.scaladsl.ClusterActorSet
import akka.cluster.typed.MultiNodeTypedClusterSpec
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.serialization.jackson.CborSerializable
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.duration._

object ClusterActorSetSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  val SnitchServiceKey = ServiceKey[AnyRef]("snitch")

  case class SetActorEvent(who: String, event: Any) extends CborSerializable

  object SetActor {
    def apply(id: String): Behavior[Any] = Behaviors.setup { ctx =>
      val snitchRouter = ctx.spawn(Routers.group(SnitchServiceKey), "router")
      snitchRouter ! SetActorEvent(id, "Started")

      Behaviors.empty
    }
  }

  commonConfig(ConfigFactory.parseString("""
        akka.loglevel = DEBUG
        akka.cluster.actor-set {
          sharding {
            # First is likely to be ignored as shard coordinator not ready
            retry-interval = 0.2s
          }
          # quick ping to make test swift
          keep-alive-interval = 1s 
        }
      """).withFallback(MultiNodeClusterSpec.clusterConfig))

}

class ClusterActorSetMultiJvmNode1 extends ClusterActorSetSpec
class ClusterActorSetMultiJvmNode2 extends ClusterActorSetSpec
class ClusterActorSetMultiJvmNode3 extends ClusterActorSetSpec

abstract class ClusterActorSetSpec
    extends MultiNodeSpec(ClusterActorSetSpec)
    with MultiNodeTypedClusterSpec
    with ScalaFutures {

  import ClusterActorSetSpec._

  val probe: TestProbe[AnyRef] = TestProbe[AnyRef]()

  "Cluster sharding in multi dc cluster" must {
    "form cluster" in {
      formCluster(first, second, third)
      runOn(first) {
        typedSystem.receptionist ! Receptionist.Register(SnitchServiceKey, probe.ref, probe.ref)
        probe.expectMessageType[Receptionist.Registered]
      }
      enterBarrier("snitch-registered")

      probe.awaitAssert({
        typedSystem.receptionist ! Receptionist.Find(SnitchServiceKey, probe.ref)
        probe.expectMessageType[Receptionist.Listing].serviceInstances(SnitchServiceKey).size should ===(1)
      }, 5.seconds)
      enterBarrier("snitch-seen")
    }

    "init actor set" in {
      ClusterActorSet(typedSystem).init(6, id => SetActor(id))
      enterBarrier("actor-set-initialized")
      runOn(first) {
        val startedIds = (1 to 6).map { _ =>
          val event = probe.expectMessageType[SetActorEvent](5.seconds)
          event.event should ===("Started")
          event.who
        }.toSet
        startedIds.size should ===(6)
      }
      enterBarrier("actor-set-started")
    }

  }
}
