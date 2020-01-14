/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import scala.concurrent.duration._

import akka.testkit.AkkaSpec

class ClusterShardingSettingsSpec extends AkkaSpec(s"""
       akka.actor.provider = cluster
       akka.remote.classic.netty.tcp.port = 0
       akka.remote.artery.canonical.port = 0
       """) {

  "ClusterShardingSettings" must {

    "passivate idle entities if `remember-entities` and `passivate-idle-entity-after` are the defaults" in {
      ClusterShardingSettings(system).shouldPassivateIdleEntities shouldEqual true
    }

    "disable passivation if `remember-entities` is enabled" in {
      ClusterShardingSettings(system).withRememberEntities(true).shouldPassivateIdleEntities shouldEqual false
    }

    "disable passivation if `remember-entities` is enabled and `passivate-idle-entity-after` is 0 or 'off'" in {
      ClusterShardingSettings(system)
        .withRememberEntities(true)
        .withPassivateIdleAfter(Duration.Zero)
        .shouldPassivateIdleEntities shouldEqual false
    }

    "disable passivation if `remember-entities` is the default and `passivate-idle-entity-after` is 0 or 'off'" in {
      ClusterShardingSettings(system)
        .withPassivateIdleAfter(Duration.Zero)
        .shouldPassivateIdleEntities shouldEqual false
    }

  }

}
