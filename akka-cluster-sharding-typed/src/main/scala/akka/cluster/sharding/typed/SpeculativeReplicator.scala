/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.Behaviors
import akka.annotation.ApiMayChange
import akka.persistence.typed.internal.PublishedEvent

/**
 * Used when sharding Active Active entities in multiple instances of sharding, for example one per DC in a Multi DC
 * Akka Cluster.
 *
 * Subscribes to locally written events through a pub sub topic and sends the seen events to all the sharded replicas
 * which can then fast forward their cross-replica event streams to improve latency but still allowing slower poll
 * frequency for the cross replica queries.
 *
 * The shard id and persistence extractor used with sharding needs to handle [[akka.persistence.typed.internal.PublishedEvent]]
 * and direct such messages to the right Active Active entity.
 */
// FIXME a better name that sounds less like a fishy car salesman?
@ApiMayChange
object SpeculativeReplicator {

  /**
   * @param selfReplica The replica id of the replica that runs on this node
   * @param replicaShardingProxies A (replica id -> sharding proxy) pair for each replica in the system.
   */
  def apply(selfReplica: String, replicaShardingProxies: Map[String, ActorRef[Any]]): Behavior[Nothing] =
    Behaviors
      .setup[Any] { context =>
        context.log.debug(
          "Subscribing to event stream to forward events to [{}] sharded replicas",
          replicaShardingProxies.size - 1)
        context.system.eventStream ! EventStream.Subscribe[PublishedEvent](context.self)

        Behaviors.receiveMessagePartial {
          case event: PublishedEvent =>
            if (context.log.isTraceEnabled)
              context.log.trace(
                "Forwarding event for persistence id [{}] sequence nr [{}] to replicas",
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
