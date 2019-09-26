/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.cluster.{ Cluster => ClassicCluster }
import akka.testkit.LongRunningTest
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.{ Matchers, WordSpecLike }

import scala.collection.{ immutable => im }
import scala.concurrent.duration._
import scala.util.Try

object JoinConfig {

  val Shards = 2

  val Key = "akka.cluster.sharding.number-of-shards"

  val baseConfig: Config =
    ConfigFactory.parseString("""
      akka.actor.provider = "cluster"
      akka.cluster.sharding.state-store-mode = "persistence"
      akka.cluster.configuration-compatibility-check.enforce-on-join = on
      akka.cluster.jmx.enabled = off
      akka.coordinated-shutdown.terminate-actor-system = on
      akka.remote.classic.netty.tcp.port = 0
      akka.remote.artery.canonical.port = 0
    """)

  def joinConfig(configured: Int): Config =
    ConfigFactory.parseString(s"$Key = $configured").withFallback(baseConfig)
}

abstract class JoinConfigCompatCheckerClusterShardingSpec
    extends ScalaTestWithActorTestKit(JoinConfig.joinConfig(JoinConfig.Shards))
    with WordSpecLike
    with Matchers
    with LogCapturing {

  protected val clusterWaitDuration = 5.seconds

  protected def join(sys: ActorSystem[_]): ClassicCluster = {
    if (sys eq system) {
      configured(system) should ===(JoinConfig.Shards)
      val seedNode = ClassicCluster(system.toClassic)
      seedNode.join(seedNode.selfAddress)
      val probe = createTestProbe()
      probe.awaitAssert(seedNode.readView.isSingletonCluster should ===(true), clusterWaitDuration)
      seedNode
    } else {
      val joiningNode = ClassicCluster(sys.toClassic)
      joiningNode.joinSeedNodes(im.Seq(ClassicCluster(system.toClassic).selfAddress))
      joiningNode
    }
  }

  protected def configured(system: ActorSystem[_]): Int =
    Try(system.settings.config.getInt(JoinConfig.Key)).getOrElse(0)

}

class JoinConfigIncompatibilitySpec extends JoinConfigCompatCheckerClusterShardingSpec {
  "A Joining Node" must {

    s"not be allowed to join a cluster with different '${JoinConfig.Key}'" taggedAs LongRunningTest in {
      join(system)
      val joining = ActorTestKit(system.name, JoinConfig.joinConfig(JoinConfig.Shards + 1)) // different
      configured(joining.system) should ===(configured(system) + 1)

      val joiningNode = join(joining.system)
      val probe = createTestProbe()
      probe.awaitAssert(joiningNode.readView.isTerminated should ===(true), clusterWaitDuration)
      joining.shutdownTestKit()
    }
  }
}

class JoinConfigCompatibilitySpec extends JoinConfigCompatCheckerClusterShardingSpec {
  "A Joining Node" must {

    s"be allowed to join a cluster with the same '${JoinConfig.Key}'" taggedAs LongRunningTest in {
      val seedNode = join(system)
      val joining = ActorTestKit(system.name, JoinConfig.joinConfig(JoinConfig.Shards)) // same
      val joinConfig = configured(joining.system)
      configured(system) should ===(joinConfig)
      join(joining.system)

      val probe = createTestProbe()
      probe.awaitAssert(seedNode.readView.members.size should ===(2), clusterWaitDuration)
      joining.shutdownTestKit()
    }
  }
}
