/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import akka.cluster.MultiNodeClusterSpec
import akka.cluster.sharding.testkit.ClusterShardingConfig
import akka.persistence.journal.leveldb.SharedLeveldbJournal
import akka.remote.testkit.MultiNodeConfig
import com.typesafe.config.{ Config, ConfigFactory }

/**
 * A MultiNodeConfig for ClusterSharding. Implement the roles, etc. and create with the following:
 *
 * @param mode the state store mode
 * @param rememberEntities defaults to off
 * @param overrides additional config
 * @param loglevel defaults to INFO
 */
abstract class MultiNodeClusterShardingConfig(
    override val mode: String = ClusterShardingSettings.StateStoreModeDData,
    override val rememberEntities: Boolean = false,
    overrides: Config = ConfigFactory.empty,
    loglevel: String = "INFO")
    extends MultiNodeConfig
    with ClusterShardingConfig {

  val modeConfig =
    if (mode == ClusterShardingSettings.StateStoreModeDData) ConfigFactory.empty
    else ConfigFactory.parseString(s"""
      akka.persistence.journal.plugin = "akka.persistence.journal.leveldb-shared"
      akka.persistence.journal.leveldb-shared.timeout = 5s
      akka.persistence.journal.leveldb-shared.store.native = off
      akka.persistence.journal.leveldb-shared.store.dir = "$targetDir/journal"
      akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
      akka.persistence.snapshot-store.local.dir = "$targetDir/snapshots"
      """)

  commonConfig(
    overrides
      .withFallback(modeConfig)
      .withFallback(baseConfig)
      .withFallback(ConfigFactory.parseString(s"""
      akka.cluster.auto-down-unreachable-after = 0s
      """))
      .withFallback(SharedLeveldbJournal.configToEnableJavaSerializationForTest)
      .withFallback(MultiNodeClusterSpec.clusterConfig))

}
