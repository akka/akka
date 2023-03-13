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

  def decodeEntityId(id: String, supportsRescale: Boolean) =
    if (supportsRescale) {
      id.split(Separator) match {
        case Array(rev, count, n) => DecodedId(rev.toLong, count.toInt, n.toInt)
        case _                    => throw new IllegalArgumentException(s"Unexpected id for sharded daemon process: '$id'")
      }
    } else {
      DecodedId(0L, 0, id.toInt) // for non-dynamic-scaling workers, total count not used
    }

  final case class DecodedId(revision: Long, totalCount: Int, processNumber: Int) {
    def encodeEntityId(supportsRescale: Boolean): String =
      if (supportsRescale) s"$revision$Separator$totalCount$Separator$processNumber"
      else processNumber.toString
  }

  final class MessageExtractor[T](supportsRescale: Boolean) extends ShardingMessageExtractor[ShardingEnvelope[T], T] {
    def entityId(message: ShardingEnvelope[T]): String = message match {
      case ShardingEnvelope(id, _) => id
    }

    // use process n for shard id
    def shardId(entityId: String): String = {
      if (supportsRescale) entityId.split(Separator)(2)
      else entityId
    }

    def unwrapMessage(message: ShardingEnvelope[T]): T = message.message
  }

  def sortedIdentitiesFor(revision: Long, numberOfProcesses: Int, supportsRescale: Boolean): Vector[String] =
    (0 until numberOfProcesses)
      .map(n => DecodedId(revision, numberOfProcesses, n).encodeEntityId(supportsRescale))
      .toVector
      .sorted

  def allShardsFor(revision: Long, numberOfProcesses: Int, supportsRescale: Boolean): Set[String] = {
    val messageExtractor = new MessageExtractor[Unit](supportsRescale)
    sortedIdentitiesFor(revision, numberOfProcesses, supportsRescale).map(messageExtractor.shardId).toSet
  }

}
