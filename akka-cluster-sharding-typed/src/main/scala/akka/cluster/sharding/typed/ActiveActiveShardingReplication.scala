/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.Behaviors
import akka.annotation.ApiMayChange
import akka.annotation.DoNotInherit
import akka.persistence.typed.PublishedEvent

/**
 * Used when sharding Active Active entities in multiple instances of sharding, for example one per DC in a Multi DC
 * Akka Cluster.
 *
 * Subscribes to locally written events through the event stream and sends the seen events to all the sharded replicas
 * which can then fast forward their cross-replica event streams to improve latency but still allowing slower poll
 * frequency for the cross replica queries. Note that since message delivery is at-most-once this can not be the only
 * channel for replica events - the entities must still tail events from the journals of other replicas.
 *
 * The shard id and persistence extractor used with sharding needs to handle [[akka.persistence.typed.PublishedEvent]]
 * and direct such messages to the right Active Active entity.
 */
@ApiMayChange
object ActiveActiveShardingReplication {

  @DoNotInherit
  sealed trait Command

  /**
   * @param selfReplica The replica id of the replica that runs on this node
   * @param replicaShardingProxies A (replica id -> sharding proxy) pair for each replica in the system.
   */
  def apply[T](selfReplica: String, replicaShardingProxies: Map[String, ActorRef[T]]): Behavior[Command] =
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
                  proxy.asInstanceOf[ActorRef[PublishedEvent]] ! event
            }
            Behaviors.same
        }
      }
      .narrow[Command]

}
