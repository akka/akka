/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.internal.pubsub.TopicRegistry
import akka.actor.typed.pubsub.Topic
import akka.actor.typed.scaladsl.Behaviors
import akka.annotation.ApiMayChange
import akka.persistence.typed.PublishedEvent

/**
 * Used when sharding Active Active entities in multiple instances of sharding, for example one per DC in a Multi DC
 * Akka Cluster.
 *
 * Subscribes to locally written events through a pub sub topic and sends the seen events to all the sharded replicas
 * which can then fast forward their cross-replica event streams to improve latency but still allowing slower poll
 * frequency for the cross replica queries.
 *
 * The shard id and persistence extractor used with sharding needs to handle [[akka.persistence.typed.PublishedEvent]]
 * and direct such messages to the right Active Active entity.
 */
// FIXME a better name that sounds less like a fishy car salesman?
@ApiMayChange
object SpeculativeReplicator {

  /**
   * @param selfReplica The replica id of the replica that runs on this node
   * @param replicaShardingProxies A (replica id -> sharding proxy) pair for each replica in the system.
   * @param eventTopicName A per-node unique topic name used as topic name with the Active Active event sourced behavior
   *                       (using the same topic name would publish to all subscribers across the cluster)
   */
  def apply(
      selfReplica: String,
      replicaShardingProxies: Map[String, ActorRef[Any]],
      eventTopicName: String): Behavior[Nothing] =
    Behaviors
      .setup[Any] { context =>
        context.log.debug(
          "Subscribing to event topic [{}] to forward events to [{}] sharded replicas",
          eventTopicName,
          replicaShardingProxies.size - 1)
        val topic = TopicRegistry(context.system).topicFor[PublishedEvent](eventTopicName)
        topic ! Topic.Subscribe(context.self)

        Behaviors.receiveMessagePartial {
          case event: PublishedEvent =>
            if (context.log.isTraceEnabled)
              context.log.trace(
                "Forwarding event for persistence id [{}] sequence nr [{}] replicas",
                event.persistenceId,
                event.sequenceNumber)
            replicaShardingProxies.foreach {
              case (replica, proxy) =>
                if (replica != selfReplica)
                  proxy ! event
            }
            Behaviors.same
        }
      }
      .narrow[Nothing]

}
