/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.annotation.InternalApi
import akka.persistence.query.EventEnvelope
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.scaladsl.EventsByPersistenceIdQuery
import akka.persistence.typed.ReplicaId
import akka.persistence.typed.ReplicatedEventHub
import akka.persistence.typed.ReplicationId
import akka.persistence.typed.ReplicationStream
import akka.persistence.typed.ReplicationStreamControl
import akka.stream.RestartSettings
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.RestartSource
import akka.stream.scaladsl.Source
import akka.util.OptionVal

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Future
import scala.concurrent.duration._

/**
 * INTERNAL API
 */
@InternalApi
final class JournalEventHub(allReplicasAndQueryPlugins: Map[ReplicaId, String])(implicit val system: ActorSystem[_])
    extends ReplicatedEventHub {
  private val query = PersistenceQuery(system)
  import system.executionContext

  override def createReplicationStream(
      replicationId: ReplicationId,
      requestLastSeenSeqNr: ReplicaId => Future[Long]): ReplicationStream = {
    val queryPluginId = allReplicasAndQueryPlugins(replicationId.replicaId)
    val replication = query.readJournalFor[EventsByPersistenceIdQuery](queryPluginId)

    val controlRef = new AtomicReference[ReplicationStreamControl]()
    val eventSource = RestartSource.withBackoff(RestartSettings(2.seconds, 10.seconds, randomFactor = 0.2)) { () =>
      Source.futureSource {
        requestLastSeenSeqNr(replicationId.replicaId).map { seqNr =>
          replication
            .eventsByPersistenceId(replicationId.persistenceId.id, seqNr + 1, Long.MaxValue)
            // from each replica, only get the events that originated there, this prevents most of the event filtering
            // the downside is that events can't be received via other replicas in the event of an uneven network partition
            .filter(event =>
              event.eventMetadata match {
                case Some(replicatedMeta: ReplicatedEventMetadata) =>
                  replicatedMeta.originReplica == replicationId.replicaId
                case _ =>
                  throw new IllegalArgumentException(
                    s"Replication stream from replica ${replicationId.replicaId} for ${replicationId.persistenceId} contains event " +
                    s"(sequence nr ${event.sequenceNr}) without replication metadata. " +
                    s"Is the persistence id used by a regular event sourced actor there or the journal for that replica (${queryPluginId}) " +
                    "used that does not support Replicated Event Sourcing?")
              })
            .viaMat(new FastForwardingFilter)(Keep.right)
            .mapMaterializedValue(streamControl => controlRef.set(streamControl))
        }
      }
    }

    new ReplicationStream {
      override def control: OptionVal[ReplicationStreamControl] = OptionVal(controlRef.get())
      override def source: Source[EventEnvelope, NotUsed] = eventSource
    }
  }
}
