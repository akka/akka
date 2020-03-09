/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed

import akka.actor.typed.ActorSystem
import akka.annotation.ApiMayChange
import akka.annotation.InternalApi
import akka.util.JavaDurationConverters._
import com.typesafe.config.Config

import scala.concurrent.duration.FiniteDuration

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

    new ShardedDaemonProcessSettings(keepAliveInterval)
  }

}

/**
 * Not for user constructions, use factory methods to instanciate.
 */
final class ShardedDaemonProcessSettings @InternalApi private[akka] (val keepAliveInterval: FiniteDuration) {

  /**
   * The interval each parent of the sharded set is pinged from each node in the cluster.
   *
   * Note: How the sharded set is kept alive may change in the future meaning this setting may go away.
   */
  @ApiMayChange
  def withKeepAliveInterval(keepAliveInterval: FiniteDuration): ShardedDaemonProcessSettings =
    new ShardedDaemonProcessSettings(keepAliveInterval)
}
