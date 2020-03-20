/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

object Whatever
/*
import java.net.URLEncoder

import akka.actor.ActorLogging
import akka.actor.Props
import akka.actor.Stash
import akka.actor.Terminated
import akka.cluster.ddata.ORSet
import akka.cluster.ddata.Replicator.GetFailure
import akka.cluster.ddata.Replicator.GetSuccess
import akka.cluster.ddata.Replicator.ModifyFailure
import akka.cluster.ddata.Replicator.NotFound
import akka.cluster.ddata.Replicator.StoreFailure
import akka.cluster.ddata.Replicator.Update
import akka.cluster.ddata.Replicator.UpdateSuccess
import akka.cluster.ddata.Replicator.UpdateTimeout
import akka.cluster.sharding.ShardCoordinator.Internal.CoordinatorMessage
import akka.cluster.sharding.ShardRegion.ShardInitialized
import akka.cluster.sharding.ShardRegion.ShardRegionCommand





/**
 * INTERNAL API
 *
 * This actor creates children entity actors on demand that it is told to be
 * responsible for. It is used when `rememberEntities` is enabled and
 * `state-store-mode=ddata`.
 *
 * @see [[ClusterSharding$ ClusterSharding extension]]
 */
private[akka] class DDataShard(
                                typeName: String,
                                shardId: ShardRegion.ShardId,
                                entityProps: String => Props,
                                settings: ClusterShardingSettings,
                                extractEntityId: ShardRegion.ExtractEntityId,
                                extractShardId: ShardRegion.ExtractShardId,
                                handOffStopMessage: Any)
  extends Shard(typeName, shardId, entityProps, settings, extractEntityId, extractShardId, handOffStopMessage)
    with Stash
    with ActorLogging {

  import Shard._
  import ShardRegion.EntityId
  import settings.tuningParameters._


  override def receive = waitingForState(Set.empty)

  // This state will stash all commands
  private def waitingForState(gotKeys: Set[Int]): Receive = {
    def receiveOne(i: Int): Unit = {
      val newGotKeys = gotKeys + i
      if (newGotKeys.size == numberOfKeys) {
        recoveryCompleted()
      } else
        context.become(waitingForState(newGotKeys))
    }

    {
      case g @ GetSuccess(_, Some(i: Int)) =>
        val key = stateKeys(i)
        state = state.copy(entityIds = state.entities.union(g.get(key).elements))
        receiveOne(i)

      case GetFailure(_, _) =>
        log.error(
          "The DDataShard was unable to get an initial state within 'waiting-for-state-timeout': {} millis",
          waitingForStateTimeout.toMillis)
        // parent ShardRegion supervisor will notice that it terminated and will start it again, after backoff
        context.stop(self)

      case NotFound(_, Some(i: Int)) =>
        receiveOne(i)

      case _ =>
        log.debug("Stashing while waiting for DDataShard initial state")
        stash()
    }
  }

  private def recoveryCompleted(): Unit = {
    log.debug("DDataShard recovery completed shard [{}] with [{}] entities", shardId, state.entities.size)
    waiting = false
    context.parent ! ShardInitialized(shardId)
    context.become(receiveCommand)
    restartRememberedEntities()
  }

  override def processChange[E <: StateChange](event: E)(handler: E => Unit): Unit = {
    waiting = true
    context.become(waitingForUpdate(event, handler), discardOld = false)
    sendUpdate(event, retryCount = 1)
  }

  private def sendUpdate(evt: StateChange, retryCount: Int): Unit = {
    replicator ! Update(key(evt.entityId), ORSet.empty[EntityId], writeMajority, Some((evt, retryCount))) { existing =>
      evt match {
        case EntityStarted(id) => existing :+ id
        case EntityStopped(id) => existing.remove(id)
      }
    }
  }



    // below cases should handle same messages as in Shard.receiveCommand
    case _: Terminated                           => stash()
    case _: CoordinatorMessage                   => stash()
    case _: ShardCommand                         => stash()
    case _: ShardRegion.StartEntity              => stash()
    case _: ShardRegion.StartEntityAck           => stash()
    case _: ShardRegionCommand                   => stash()
    case msg: ShardQuery                         => receiveShardQuery(msg)
    case PassivateIdleTick                       => stash()
    case msg: LeaseLost                          => receiveLeaseLost(msg)
    case msg if extractEntityId.isDefinedAt(msg) => deliverOrBufferMessage(msg, evt)
    case msg                                     =>
      // shouldn't be any other message types, but just in case
      log.debug("Stashing unexpected message [{}] while waiting for DDataShard update of {}", msg.getClass, evt)
      stash()
  }



}
 */
