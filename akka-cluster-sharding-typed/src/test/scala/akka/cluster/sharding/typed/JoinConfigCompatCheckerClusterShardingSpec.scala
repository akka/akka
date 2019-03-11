/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed

import scala.concurrent.duration._
import scala.collection.{ immutable => im }
import scala.util.Try

import akka.actor.ActorSystem
import akka.cluster.Cluster
import akka.testkit.{ AkkaSpec, LongRunningTest }
import com.typesafe.config.{ Config, ConfigFactory }

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
      akka.remote.netty.tcp.port = 0
      akka.remote.artery.canonical.port = 0
    """).withFallback(AkkaSpec.testConf)

  def joinConfig(configured: Int): Config =
    ConfigFactory.parseString(s"$Key = $configured").withFallback(baseConfig)
}

abstract class JoinConfigCompatCheckerClusterShardingSpec extends AkkaSpec(JoinConfig.joinConfig(JoinConfig.Shards)) {

  protected val duration = 5.seconds

  protected var nodes: Seq[ActorSystem] = Seq.empty

  override protected def beforeTermination(): Unit = nodes.foreach(shutdown(_))

  protected def join(sys: ActorSystem): Cluster = {
    nodes :+= sys
    sys match {
      case seed if seed eq system =>
        configured(system) should ===(JoinConfig.Shards)
        val seedNode = Cluster(seed)
        seedNode.join(seedNode.selfAddress)
        awaitCond(seedNode.readView.isSingletonCluster, duration)
        seedNode
      case joining =>
        val cluster = Cluster(joining)
        cluster.joinSeedNodes(im.Seq(Cluster(system).readView.selfAddress))
        cluster
    }
  }

  protected def configured(system: ActorSystem): Int =
    Try(system.settings.config.getInt(JoinConfig.Key)).getOrElse(0)

}

class JoinConfigIncompatibilitySpec extends JoinConfigCompatCheckerClusterShardingSpec {
  "A Joining Node" must {

    s"not be allowed to join a cluster with different '${JoinConfig.Key}'" taggedAs LongRunningTest in {
      join(system)
      val joining = ActorSystem(system.name, JoinConfig.joinConfig(JoinConfig.Shards + 1)) // different
      configured(joining) should ===(configured(system) + 1)

      val cluster = join(joining)
      awaitCond(cluster.readView.isTerminated, duration)
    }
  }
}

class JoinConfigCompatibilitySpec extends JoinConfigCompatCheckerClusterShardingSpec {
  "A Joining Node" must {

    s"be allowed to join a cluster with the same '${JoinConfig.Key}'" taggedAs LongRunningTest in {
      val seedNode = join(system)
      val joining = ActorSystem(system.name, JoinConfig.joinConfig(JoinConfig.Shards)) // same
      val joinConfig = configured(joining)
      configured(system) should ===(joinConfig)

      val cluster = join(joining)
      for {
        node <- Set(seedNode, cluster)
      } awaitCond(node.readView.members.size == joinConfig, duration)
    }
  }
}
