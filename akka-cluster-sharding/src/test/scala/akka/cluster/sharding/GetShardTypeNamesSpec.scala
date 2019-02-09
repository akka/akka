/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import akka.actor.Props
import akka.cluster.Cluster
import akka.testkit.AkkaSpec
import akka.testkit.TestActors.EchoActor

object GetShardTypeNamesSpec {
  val config =
    """
    akka.loglevel = INFO
    akka.actor.provider = "cluster"
    akka.remote.netty.tcp.port = 0
    akka.remote.artery.canonical.port = 0
    """

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case msg: Int => (msg.toString, msg)
  }

  val extractShardId: ShardRegion.ExtractShardId = {
    case msg: Int => (msg % 10).toString
  }
}

class GetShardTypeNamesSpec extends AkkaSpec(GetShardTypeNamesSpec.config) {
  import GetShardTypeNamesSpec._

  "GetShardTypeNames" must {
    "contain empty when join cluster without shards" in {
      ClusterSharding(system).shardTypeNames should ===(Set())
    }

    "contain started shards when started 2 shards" in {
      Cluster(system).join(Cluster(system).selfAddress)
      val settings = ClusterShardingSettings(system)
      ClusterSharding(system).start("type1", Props[EchoActor](), settings, extractEntityId, extractShardId)
      ClusterSharding(system).start("type2", Props[EchoActor](), settings, extractEntityId, extractShardId)

      ClusterSharding(system).shardTypeNames should ===(Set("type1", "type2"))
    }
  }
}
