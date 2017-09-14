/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.cluster

import akka.cluster.sharding.ShardCoordinator.ShardAllocationStrategy
import akka.cluster.sharding.{ ClusterShardingSettings, ShardRegion }
import akka.typed.cluster.ClusterSharding.TypedMessageExtractor
import akka.typed.{ ActorRef, ActorSystem, Behavior, Extension, ExtensionId, Props }

object ClusterSharding extends ExtensionId[ClusterSharding] {

  /**
   *
   * @tparam E Possibly an envelope around the messages accepted by the entity actor, is the same as `A` if there is no
   *           envelope.
   * @tparam A The type of message accepted by the entity actor
   */
  trait TypedMessageExtractor[E, A] {

    // TODO what about StartEntity

    /**
     * Extract the entity id from an incoming `message`. If `null` is returned
     * the message will be `unhandled`, i.e. posted as `Unhandled` messages on the event stream
     */
    def entityId(message: E): String

    /**
     * Extract the message to send to the entity from an incoming `message`.
     * Note that the extracted message does not have to be the same as the incoming
     * message to support wrapping in message envelope that is unwrapped before
     * sending to the entity actor.
     */
    def entityMessage(message: E): A

    /**
     * Extract the entity id from an incoming `message`. Only messages that passed the [[#entityId]]
     * function will be used as input to this function.
     */
    def shardId(message: E): String
  }

  def createExtension(system: ActorSystem[_]): ClusterSharding = ???
}

trait ClusterSharding extends Extension {

  /**
   * Spawn a shard region or a proxy depending on if the settings require role and if this node has such a role.
   *
   * @param behavior The behavior for entities
   * @param typeName A name that uniquely identifies the type of entity in this cluster
   * @param entityProps Props to apply when starting an entity
   * @param handOffStopMessage Message sent to an entity to tell it to stop
   * @tparam E A possible envelope around the message the entity accepts
   * @tparam A The type of command the entity accepts
   */
  def spawn[E, A](
    behavior:           Behavior[A],
    typeName:           String,
    entityProps:        Props,
    settings:           ClusterShardingSettings,
    messageExtractor:   TypedMessageExtractor[E, A],
    allocationStrategy: ShardAllocationStrategy,
    handOffStopMessage: A
  ): ActorRef[E]

  def spawn[E, A](
    behavior:           Behavior[A],
    typeName:           String,
    settings:           ClusterShardingSettings,
    messageExtractor:   TypedMessageExtractor[E, A],
    handOffStopMessage: A
  ): ActorRef[E]

}
