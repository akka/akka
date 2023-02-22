/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed

import akka.Done
import akka.actor.typed.ActorRef

import java.time.Duration
import scala.concurrent.duration.FiniteDuration
import com.typesafe.config.Config
import akka.actor.typed.ActorSystem
import akka.annotation.DoNotInherit
import akka.annotation.InternalApi
import akka.pattern.StatusReply
import akka.util.JavaDurationConverters._

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
    val keepAliveFromNumberOfNodes = config.getInt("keep-alive-from-number-of-nodes")
    val keepAliveThrottleInterval = config.getDuration("keep-alive-throttle-interval").asScala

    new ShardedDaemonProcessSettings(
      keepAliveInterval,
      None,
      None,
      keepAliveFromNumberOfNodes,
      keepAliveThrottleInterval)
  }

}

/**
 * Not for user constructions, use factory methods to instantiate.
 */
final class ShardedDaemonProcessSettings @InternalApi private[akka] (
    val keepAliveInterval: FiniteDuration,
    val shardingSettings: Option[ClusterShardingSettings],
    val role: Option[String],
    val keepAliveFromNumberOfNodes: Int,
    val keepAliveThrottleInterval: FiniteDuration) {

  /**
   * Scala API: The interval each parent of the sharded set is pinged from each node in the cluster.
   *
   * Note: How the sharded set is kept alive may change in the future meaning this setting may go away.
   */
  def withKeepAliveInterval(keepAliveInterval: FiniteDuration): ShardedDaemonProcessSettings =
    copy(keepAliveInterval = keepAliveInterval)

  /**
   * Java API: The interval each parent of the sharded set is pinged from each node in the cluster.
   *
   * Note: How the sharded set is kept alive may change in the future meaning this setting may go away.
   */
  def withKeepAliveInterval(keepAliveInterval: Duration): ShardedDaemonProcessSettings =
    copy(keepAliveInterval = keepAliveInterval.asScala)

  /**
   * Specify sharding settings that should be used for the sharded daemon process instead of loading from config.
   * Some settings can not be changed (remember-entitites and related settings, passivation, number-of-shards),
   * changing those settings will be ignored.
   */
  def withShardingSettings(shardingSettings: ClusterShardingSettings): ShardedDaemonProcessSettings =
    copy(shardingSettings = Option(shardingSettings))

  /**
   * Specifies that the ShardedDaemonProcess should run on nodes with a specific role.
   * If the role is not specified all nodes in the cluster are used. If the given role does
   * not match the role of the current node the the ShardedDaemonProcess will not be started.
   */
  def withRole(role: String): ShardedDaemonProcessSettings =
    copy(role = Option(role))

  /**
   * Keep alive messages from this number of nodes.
   */
  def withKeepAliveFromNumberOfNodes(keepAliveFromNumberOfNodes: Int): ShardedDaemonProcessSettings =
    copy(keepAliveFromNumberOfNodes = keepAliveFromNumberOfNodes)

  /**
   * Scala API: Keep alive messages are sent with this delay between each message.
   */
  def withKeepAliveThrottleInterval(keepAliveThrottleInterval: FiniteDuration): ShardedDaemonProcessSettings =
    copy(keepAliveThrottleInterval = keepAliveThrottleInterval)

  /**
   * Java API: Keep alive messages are sent with this delay between each message.
   */
  def withKeepAliveThrottleInterval(keepAliveThrottleInterval: Duration): ShardedDaemonProcessSettings =
    copy(keepAliveThrottleInterval = keepAliveThrottleInterval.asScala)

  private def copy(
      keepAliveInterval: FiniteDuration = keepAliveInterval,
      shardingSettings: Option[ClusterShardingSettings] = shardingSettings,
      role: Option[String] = role,
      keepAliveFromNumberOfNodes: Int = keepAliveFromNumberOfNodes,
      keepAliveThrottleInterval: FiniteDuration = keepAliveThrottleInterval): ShardedDaemonProcessSettings =
    new ShardedDaemonProcessSettings(
      keepAliveInterval,
      shardingSettings,
      role,
      keepAliveFromNumberOfNodes,
      keepAliveThrottleInterval)

}

/**
 * Context with details about the Sharded Daemon Process instance to use when starting it
 *
 * Not for user extension
 */
@DoNotInherit
trait ShardedDaemonProcessContext {
  def processNumber: Int
  def totalProcesses: Int

  def name: String

}

/**
 * Commands for interacting with the sharded daemon process
 *
 * Not for user extension
 */
@DoNotInherit
trait ShardedDaemonProcessCommand {}

/**
 * Tell the sharded daemon process to rescale to the given number of processes.
 *
 * @param newNumberOfProcesses The number of processes to scale up to
 * @param replyTo Reply to this actor once scaling is successfully done, or with details if it failed
 */
final case class ChangeNumberOfProcesses(newNumberOfProcesses: Int, replyTo: ActorRef[StatusReply[Done]])
    extends ShardedDaemonProcessCommand
