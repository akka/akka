/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.internal

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.NoSerializationVerificationNeeded
import akka.actor.Props
import akka.annotation.InternalApi
import akka.cluster.sharding.ClusterShardingSettings
import akka.cluster.sharding.ShardRegion

import scala.collection.immutable.Set

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object RememberEntityStarter {
  def props(region: ActorRef, ids: Set[ShardRegion.EntityId], settings: ClusterShardingSettings, requestor: ActorRef) =
    Props(new RememberEntityStarter(region, ids, settings, requestor))

  private case object Tick extends NoSerializationVerificationNeeded
}

/**
 * INTERNAL API: Actor responsible for starting entities when rememberEntities is enabled
 */
@InternalApi
private[akka] final class RememberEntityStarter(
    region: ActorRef,
    ids: Set[ShardRegion.EntityId],
    settings: ClusterShardingSettings,
    requestor: ActorRef)
    extends Actor
    with ActorLogging {

  import RememberEntityStarter.Tick
  import context.dispatcher

  var waitingForAck = ids

  sendStart(ids)

  val tickTask = {
    val resendInterval = settings.tuningParameters.retryInterval
    context.system.scheduler.scheduleWithFixedDelay(resendInterval, resendInterval, self, Tick)
  }

  def sendStart(ids: Set[ShardRegion.EntityId]): Unit = {
    // these go through the region rather the directly to the shard
    // so that shard mapping changes are picked up
    ids.foreach(id => region ! ShardRegion.StartEntity(id))
  }

  override def receive: Receive = {
    case ack: ShardRegion.StartEntityAck =>
      waitingForAck -= ack.entityId
      // inform whoever requested the start that it happened
      requestor ! ack
      if (waitingForAck.isEmpty) context.stop(self)

    case Tick =>
      sendStart(waitingForAck)

  }

  override def postStop(): Unit = {
    tickTask.cancel()
  }
}
