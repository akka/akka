/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed

import java.time.Duration

import scala.concurrent.duration.FiniteDuration

import com.typesafe.config.Config

import akka.actor.typed.ActorSystem
import akka.annotation.ApiMayChange
import akka.annotation.InternalApi
import akka.util.JavaDurationConverters._

@ApiMayChange
object ShardedDaemonProcessSettings {

  /** Scala API: Create default settings for system */
  def apply(system: ActorSystem[_]): ShardedDaemonProcessSettings = {
    fromConfig(system.settings.config.getConfig("akka.cluster.sharded-daemon-process"))
  }

  /** Java API: Create default settings for system */
  def create(system: ActorSystem[_]): ShardedDaemonProcessSettings =
    apply(system)

  /**
   * Load settings from a specific config location.
   */
  def fromConfig(config: Config): ShardedDaemonProcessSettings = {
    val keepAliveInterval = config.getDuration("keep-alive-interval").asScala

    new ShardedDaemonProcessSettings(keepAliveInterval, None)
  }

}

/**
 * Not for user constructions, use factory methods to instanciate.
 */
@ApiMayChange
final class ShardedDaemonProcessSettings @InternalApi private[akka] (
    val keepAliveInterval: FiniteDuration,
    val shardingSettings: Option[ClusterShardingSettings]) {

  /**
   * Scala API: The interval each parent of the sharded set is pinged from each node in the cluster.
   *
   * Note: How the sharded set is kept alive may change in the future meaning this setting may go away.
   */
  def withKeepAliveInterval(keepAliveInterval: FiniteDuration): ShardedDaemonProcessSettings =
    new ShardedDaemonProcessSettings(keepAliveInterval, shardingSettings)

  /**
   * Java API: The interval each parent of the sharded set is pinged from each node in the cluster.
   *
   * Note: How the sharded set is kept alive may change in the future meaning this setting may go away.
   */
  def withKeepAliveInterval(keepAliveInterval: Duration): ShardedDaemonProcessSettings =
    new ShardedDaemonProcessSettings(keepAliveInterval.asScala, shardingSettings)

  /**
   * Specify sharding settings that should be used for the sharded daemon process instead of loading from config.
   * Some settings can not be changed (remember-entitites and related settings, passivation, number-of-shards),
   * changing those settings will be ignored.
   */
  def withShardingSettings(shardingSettings: ClusterShardingSettings): ShardedDaemonProcessSettings =
    new ShardedDaemonProcessSettings(keepAliveInterval, Some(shardingSettings))
}
