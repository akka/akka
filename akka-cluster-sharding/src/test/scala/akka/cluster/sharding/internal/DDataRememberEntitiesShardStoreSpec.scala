/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.internal

import akka.cluster.ddata.{ Replicator, ReplicatorSettings }
import akka.cluster.sharding.ClusterShardingSettings
import akka.cluster.{ Cluster, MemberStatus }
import akka.testkit.{ AkkaSpec, ImplicitSender, WithLogCapturing }
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

object DDataRememberEntitiesShardStoreSpec {
  def config = ConfigFactory.parseString("""
      akka.loglevel=DEBUG
      akka.loggers = ["akka.testkit.SilenceAllTestEventListener"]
      akka.actor.provider = cluster
      akka.remote.artery.canonical.port = 0
      akka.remote.classic.netty.tcp.port = 0
      akka.cluster.sharding.state-store-mode = ddata
      akka.cluster.sharding.remember-entities = on
      # no leaks between test runs thank you
      akka.cluster.sharding.distributed-data.durable.keys = []
    """.stripMargin)
}

// FIXME generalize to general test and cover both ddata and eventsourced
class DDataRememberEntitiesShardStoreSpec
    extends AkkaSpec(DDataRememberEntitiesShardStoreSpec.config)
    with AnyWordSpecLike
    with ImplicitSender
    with WithLogCapturing {

  override def atStartup(): Unit = {
    // Form a one node cluster
    val cluster = Cluster(system)
    cluster.join(cluster.selfAddress)
    awaitAssert(cluster.readView.members.count(_.status == MemberStatus.Up) should ===(1))
  }

  "The DDataRememberEntitiesShardStore" must {

    "store starts and stops and list remembered entity ids" in {
      val replicatorSettings = ReplicatorSettings(system)
      val replicator = system.actorOf(Replicator.props(replicatorSettings))

      val shardingSettings = ClusterShardingSettings(system)
      val store = system.actorOf(
        DDataRememberEntitiesShardStore
          .props("FakeShardId", "FakeTypeName", shardingSettings, replicator, majorityMinCap = 1))

      store ! RememberEntitiesShardStore.GetEntities
      expectMsgType[RememberEntitiesShardStore.RememberedEntities].entities should be(empty)

      store ! RememberEntitiesShardStore.Update(Set("1", "2", "3"), Set.empty)
      expectMsg(RememberEntitiesShardStore.UpdateDone(Set("1", "2", "3"), Set.empty))

      store ! RememberEntitiesShardStore.Update(Set("4", "5", "6"), Set("2", "3"))
      expectMsg(RememberEntitiesShardStore.UpdateDone(Set("4", "5", "6"), Set("2", "3")))

      store ! RememberEntitiesShardStore.Update(Set.empty, Set("6"))
      expectMsg(RememberEntitiesShardStore.UpdateDone(Set.empty, Set("6")))

      store ! RememberEntitiesShardStore.Update(Set("2"), Set.empty)
      expectMsg(RememberEntitiesShardStore.UpdateDone(Set("2"), Set.empty))

      store ! RememberEntitiesShardStore.GetEntities
      expectMsgType[RememberEntitiesShardStore.RememberedEntities].entities should ===(Set("1", "2", "4", "5"))

    }

  }

}
