/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.testkit

import akka.cluster.sharding.ClusterShardingSettings
import akka.testkit.AkkaSpec
import com.typesafe.config.{ Config, ConfigFactory }

trait ClusterShardingConfig {

  val mode: String = ClusterShardingSettings.StateStoreModeDData

  val rememberEntities: Boolean = false

  val loglevel: String = "INFO"

  val targetDir = s"target/ClusterSharding${AkkaSpec.getCallerName(getClass)}Spec-$mode-remember-$rememberEntities"

  val baseConfig: Config =
    ConfigFactory.parseString(s"""
          akka.loglevel = $loglevel
          akka.actor.provider = "cluster"
          akka.remote.log-remote-lifecycle-events = off
          akka.cluster.jmx.enabled = off
          akka.cluster.sharding.state-store-mode = "$mode"
          akka.cluster.sharding.distributed-data.durable.lmdb {
            dir = $targetDir/sharding-ddata
            map-size = 10 MiB
          }
          akka.remote.classic.netty.tcp.port = 0
          akka.remote.artery.canonical.port = 0
          """)
}
