/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.internal

import akka.annotation.InternalApi
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.ShardingMessageExtractor

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object ShardedDaemonProcessId {

  /*
   * The entity id used for sharded daemon process has the format: [revision]|[total-count]|[this-process-id]
   *
   * Pingers repeatedly ping each worker id for a given number of processes to keep them alive. When rescaling
   * to a new number of workers the revision is increased so that we can detect pings for old processes and avoid
   * starting old workers anew.
   */
  val Separator = '|'

  def decodeEntityId(id: String) = {
    id.split(Separator) match {
      case Array(rev, count, n) => DecodedId(rev.toLong, count.toInt, n.toInt)
      case _                    => throw new IllegalArgumentException(s"Unexpected id for sharded daemon process: '$id'")
    }

  }

  final case class DecodedId(revision: Long, totalCount: Int, processNumber: Int) {
    def encodeEntityId: String = s"$revision$Separator$totalCount$Separator$processNumber"
  }

  final class MessageExtractor[T] extends ShardingMessageExtractor[ShardingEnvelope[T], T] {
    def entityId(message: ShardingEnvelope[T]): String = message match {
      case ShardingEnvelope(id, _) => id
    }

    // use process n for shard id
    def shardId(entityId: String): String = entityId.split(Separator)(2)

    def unwrapMessage(message: ShardingEnvelope[T]): T = message.message
  }

  def sortedIdentitiesFor(revision: Int, numberOfProcesses: Int): Vector[String] =
    (0 until numberOfProcesses).map(n => DecodedId(revision, numberOfProcesses, n).encodeEntityId).toVector.sorted

  private val messageExtractor = new MessageExtractor[Unit]

  def allShardsFor(revision: Int, numberOfProcesses: Int): Set[String] =
    sortedIdentitiesFor(revision, numberOfProcesses).map(messageExtractor.shardId).toSet

}
