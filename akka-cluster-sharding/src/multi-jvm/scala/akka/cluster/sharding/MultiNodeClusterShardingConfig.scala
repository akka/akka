/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import java.lang.reflect.Modifier

import akka.cluster.MultiNodeClusterSpec
import akka.persistence.journal.leveldb.SharedLeveldbJournal
import akka.remote.testkit.MultiNodeConfig
import com.typesafe.config.{ Config, ConfigFactory }

object MultiNodeClusterShardingConfig {
  private[sharding] def testNameFromCallStack(classToStartFrom: Class[_]): String = {

    def isAbstractClass(className: String): Boolean = {
      try {
        Modifier.isAbstract(Class.forName(className).getModifiers)
      } catch {
        case _: Throwable => false // yes catch everything, best effort check
      }
    }

    val startFrom = classToStartFrom.getName
    val filteredStack = Thread.currentThread.getStackTrace.iterator
      .map(_.getClassName)
      // drop until we find the first occurrence of classToStartFrom
      .dropWhile(!_.startsWith(startFrom))
      // then continue to the next entry after classToStartFrom that makes sense
      .dropWhile {
        case `startFrom`                            => true
        case str if str.startsWith(startFrom + "$") => true // lambdas inside startFrom etc
        case str if isAbstractClass(str)            => true
        case _                                      => false
      }

    if (filteredStack.isEmpty)
      throw new IllegalArgumentException(s"Couldn't find [${classToStartFrom.getName}] in call stack")

    // sanitize for actor system name
    scrubActorSystemName(filteredStack.next())
  }

  /**
   * Sanitize the `name` to be used as valid actor system name by
   * replacing invalid characters. `name` may for example be a fully qualified
   * class name and then the short class name will be used.
   */
  def scrubActorSystemName(name: String): String = {
    name
      .replaceFirst("""^.*\.""", "") // drop package name
      .replaceAll("""\$\$?\w+""", "") // drop scala anonymous functions/classes
      .replaceAll("[^a-zA-Z_0-9]", "_")
  }
}

/**
 * A MultiNodeConfig for ClusterSharding. Implement the roles, etc. and create with the following:
 *
 * Note that this class is not used anywhere yet, but could be a good starting point
 * for new or refactored multi-node sharding specs
 *
 * @param mode the state store mode
 * @param rememberEntities defaults to off
 * @param overrides additional config
 * @param loglevel defaults to INFO
 */
abstract class MultiNodeClusterShardingConfig(
    val mode: String = ClusterShardingSettings.StateStoreModeDData,
    val rememberEntities: Boolean = false,
    overrides: Config = ConfigFactory.empty,
    loglevel: String = "INFO")
    extends MultiNodeConfig {

  import MultiNodeClusterShardingConfig._

  val targetDir =
    s"target/ClusterSharding${testNameFromCallStack(classOf[MultiNodeClusterShardingConfig])}Spec-$mode-remember-$rememberEntities"

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
      .withFallback(ConfigFactory.parseString(s"""
      akka.loglevel = $loglevel
      akka.actor.provider = "cluster"
      akka.cluster.downing-provider-class = akka.cluster.testkit.AutoDowning
      akka.cluster.testkit.auto-down-unreachable-after = 0s
      akka.remote.log-remote-lifecycle-events = off
      akka.cluster.sharding.state-store-mode = "$mode"
      akka.cluster.sharding.distributed-data.durable.lmdb {
        dir = $targetDir/sharding-ddata
        map-size = 10 MiB
      }
      """))
      .withFallback(SharedLeveldbJournal.configToEnableJavaSerializationForTest)
      .withFallback(MultiNodeClusterSpec.clusterConfig))

}
