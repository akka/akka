/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed

import akka.NotUsed
import akka.annotation.ApiMayChange
import akka.annotation.DoNotInherit
import akka.annotation.InternalApi
import akka.persistence.query.EventEnvelope
import akka.stream.scaladsl.Source
import akka.util.OptionVal

import scala.concurrent.Future

/**
 * Not for user extension
 */
@ApiMayChange
@InternalApi
private[akka] trait ReplicationStreamControl {
  def fastForward(sequenceNumber: Long): Unit
}

/**
 * Not for user extension
 */
@ApiMayChange
@DoNotInherit
trait ReplicationStream {
  def control: OptionVal[ReplicationStreamControl]
  def source: Source[EventEnvelope, NotUsed]
}

/**
 * Not for user extension
 */
@ApiMayChange
@DoNotInherit
trait ReplicatedEventHub {

  def createReplicationStream(
      replicationId: ReplicationId,
      requestLastSeenSeqNr: ReplicaId => Future[Long]): ReplicationStream

}
