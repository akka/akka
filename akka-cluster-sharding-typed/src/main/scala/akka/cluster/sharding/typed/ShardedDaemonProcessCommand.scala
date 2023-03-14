/*
 * Copyright (C) 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed

import akka.Done
import akka.actor.typed.ActorRef
import akka.annotation.DoNotInherit
import akka.cluster.sharding.typed.internal.ClusterShardingTypedSerializable
import akka.pattern.StatusReply

import java.time.Instant

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
 *                Note that a successful response may take a long time, depending on how fast
 *                the daemon process actors stop after getting their stop message.
 */
final case class ChangeNumberOfProcesses(newNumberOfProcesses: Int, replyTo: ActorRef[StatusReply[Done]])
    extends ShardedDaemonProcessCommand
    with ClusterShardingTypedSerializable

/**
 * Query the sharded daemon process for the current scale
 */
final case class GetNumberOfProcesses(replyTo: ActorRef[NumberOfProcesses])
    extends ShardedDaemonProcessCommand
    with ClusterShardingTypedSerializable

/**
 * Reply for [[GetNumberOfProcesses]]
 *
 * Not for user extension
 */
@DoNotInherit
trait NumberOfProcesses {

  def numberOfProcesses: Int

  /**
   * The timestamp when the change to the current number of processes was initiated. If the number is the initial
   * number of processes this value is "some time" after cluster startup.
   */
  def started: Instant

  def rescaleInProgress: Boolean

  /**
   * Revision number increased for every re-scale that has been triggered with [[ChangeNumberOfProcesses]]
   */
  def revision: Long
}
