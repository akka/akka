/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import akka.cluster.MultiNodeClusterSpec
import akka.remote.testkit.{ MultiNodeConfig, MultiNodeSpec }
import akka.testkit._
import com.typesafe.config.ConfigFactory

object ClusterShardingIncorrectSetupSpecConfig extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  val commonConfig = ConfigFactory.parseString(
    """
      akka.loglevel = INFO
      akka.cluster.sharding {
         waiting-for-state-timeout = 100ms
      }
    """.stripMargin)

  commonConfig(commonConfig.withFallback(MultiNodeClusterSpec.clusterConfig))
}

class ClusterShardingIncorrectSetupMultiJvmNode1 extends ClusterShardingIncorrectSetupSpec
class ClusterShardingIncorrectSetupMultiJvmNode2 extends ClusterShardingIncorrectSetupSpec

object ClusterShardingIncorrectSetupSpec {
  val extractEntityId: ShardRegion.ExtractEntityId = {
    case id: Int ⇒ (id.toString, id)
  }

  val extractShardId: ShardRegion.ExtractShardId = {
    case id: Int ⇒ id.toString
  }
}

abstract class ClusterShardingIncorrectSetupSpec extends MultiNodeSpec(ClusterShardingIncorrectSetupSpecConfig) with MultiNodeClusterSpec with ImplicitSender {

  import ClusterShardingIncorrectSetupSpec._
  import ClusterShardingIncorrectSetupSpecConfig._

  "Cluster sharding" must {
    "log useful error message if sharding not started" in {
      awaitClusterUp(roles: _*)
      enterBarrier("cluster-up")
      runOn(first) {
        EventFilter.error(pattern = """Has ClusterSharding been started on all nodes?""").intercept {
          ClusterSharding(system).start(
            typeName = "Entity",
            entityProps = TestActors.echoActorProps,
            settings = ClusterShardingSettings(system),
            extractEntityId = extractEntityId,
            extractShardId = extractShardId)
        }
      }
      enterBarrier("helpful error message logged")
    }
  }
}

