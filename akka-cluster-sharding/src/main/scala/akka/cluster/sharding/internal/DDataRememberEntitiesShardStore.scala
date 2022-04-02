/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.internal

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.Stash
import akka.annotation.InternalApi
import akka.cluster.Cluster
import akka.cluster.ddata.ORSet
import akka.cluster.ddata.ORSetKey
import akka.cluster.ddata.Replicator.Get
import akka.cluster.ddata.Replicator.GetDataDeleted
import akka.cluster.ddata.Replicator.GetFailure
import akka.cluster.ddata.Replicator.GetSuccess
import akka.cluster.ddata.Replicator.ModifyFailure
import akka.cluster.ddata.Replicator.NotFound
import akka.cluster.ddata.Replicator.ReadMajority
import akka.cluster.ddata.Replicator.StoreFailure
import akka.cluster.ddata.Replicator.Update
import akka.cluster.ddata.Replicator.UpdateDataDeleted
import akka.cluster.ddata.Replicator.UpdateSuccess
import akka.cluster.ddata.Replicator.UpdateTimeout
import akka.cluster.ddata.Replicator.WriteMajority
import akka.cluster.ddata.SelfUniqueAddress
import akka.cluster.sharding.ClusterShardingSettings
import akka.cluster.sharding.ShardRegion.EntityId
import akka.cluster.sharding.ShardRegion.ShardId
import akka.util.PrettyDuration._

import scala.concurrent.ExecutionContext

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object DDataRememberEntitiesShardStore {

  def props(
      shardId: ShardId,
      typeName: String,
      settings: ClusterShardingSettings,
      replicator: ActorRef,
      majorityMinCap: Int): Props =
    Props(new DDataRememberEntitiesShardStore(shardId, typeName, settings, replicator, majorityMinCap))

  // The default maximum-frame-size is 256 KiB with Artery.
  // When using entity identifiers with 36 character strings (e.g. UUID.randomUUID).
  // By splitting the elements over 5 keys we can support 10000 entities per shard.
  // The Gossip message size of 5 ORSet with 2000 ids is around 200 KiB.
  // This is by intention not configurable because it's important to have the same
  // configuration on each node.
  private val numberOfKeys = 5

  private def stateKeys(typeName: String, shardId: ShardId): Array[ORSetKey[EntityId]] =
    Array.tabulate(numberOfKeys)(i => ORSetKey[EntityId](s"shard-$typeName-$shardId-$i"))

  private sealed trait Evt {
    def id: EntityId
  }
  private case class Started(id: EntityId) extends Evt
  private case class Stopped(id: EntityId) extends Evt

}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final class DDataRememberEntitiesShardStore(
    shardId: ShardId,
    typeName: String,
    settings: ClusterShardingSettings,
    replicator: ActorRef,
    majorityMinCap: Int)
    extends Actor
    with Stash
    with ActorLogging {

  import DDataRememberEntitiesShardStore._

  implicit val ec: ExecutionContext = context.dispatcher
  implicit val node: Cluster = Cluster(context.system)
  implicit val selfUniqueAddress: SelfUniqueAddress = SelfUniqueAddress(node.selfUniqueAddress)

  private val readMajority = ReadMajority(settings.tuningParameters.waitingForStateTimeout, majorityMinCap)
  // Note that the timeout is actually updatingStateTimeout / 4 so that we fit 3 retries and a response in the timeout before the shard sees it as a failure
  private val writeMajority = WriteMajority(settings.tuningParameters.updatingStateTimeout / 4, majorityMinCap)
  private val maxUpdateAttempts = 3
  private val keys = stateKeys(typeName, shardId)

  if (log.isDebugEnabled) {
    log.debug(
      "Starting up DDataRememberEntitiesStore, read timeout: [{}], write timeout: [{}], majority min cap: [{}]",
      settings.tuningParameters.waitingForStateTimeout.pretty,
      settings.tuningParameters.updatingStateTimeout.pretty,
      majorityMinCap)
  }
  loadAllEntities()

  private def key(entityId: EntityId): ORSetKey[EntityId] = {
    val i = math.abs(entityId.hashCode % numberOfKeys)
    keys(i)
  }

  override def receive: Receive = {
    waitingForAllEntityIds(Set.empty, Set.empty, None)
  }

  def idle: Receive = {
    case RememberEntitiesShardStore.GetEntities =>
      // not supported, but we may get several if the shard timed out and retried
      log.debug("Another get entities request after responding to one, not expected/supported, ignoring")
    case update: RememberEntitiesShardStore.Update => onUpdate(update)
  }

  def waitingForAllEntityIds(gotKeys: Set[Int], ids: Set[EntityId], shardWaiting: Option[ActorRef]): Receive = {
    def receiveOne(i: Int, idsForKey: Set[EntityId]): Unit = {
      val newGotKeys = gotKeys + i
      val newIds = ids.union(idsForKey)
      if (newGotKeys.size == numberOfKeys) {
        shardWaiting match {
          case Some(shard) =>
            log.debug("Shard waiting for remembered entities, sending remembered and going idle")
            shard ! RememberEntitiesShardStore.RememberedEntities(newIds)
            context.become(idle)
            unstashAll()
          case None =>
            // we haven't seen request yet
            log.debug("Got remembered entities, waiting for shard to request them")
            context.become(waitingForAllEntityIds(newGotKeys, newIds, None))
        }
      } else {
        context.become(waitingForAllEntityIds(newGotKeys, newIds, shardWaiting))
      }
    }

    {
      case g @ GetSuccess(_, Some(i: Int)) =>
        val key = keys(i)
        val ids = g.get(key).elements
        receiveOne(i, ids)
      case NotFound(_, Some(i: Int)) =>
        receiveOne(i, Set.empty)
      case GetFailure(key, _) =>
        log.error(
          "Unable to get an initial state within 'waiting-for-state-timeout': [{}] using [{}] (key [{}])",
          readMajority.timeout.pretty,
          readMajority,
          key)
        context.stop(self)
      case GetDataDeleted(_, _) =>
        log.error("Unable to get an initial state because it was deleted")
        context.stop(self)
      case update: RememberEntitiesShardStore.Update =>
        log.warning("Got an update before load of initial entities completed, dropping update: [{}]", update)
      case RememberEntitiesShardStore.GetEntities =>
        if (gotKeys.size == numberOfKeys) {
          // we already got all and was waiting for a request
          log.debug("Got request from shard, sending remembered entities")
          sender() ! RememberEntitiesShardStore.RememberedEntities(ids)
          context.become(idle)
          unstashAll()
        } else {
          // we haven't seen all ids yet
          log.debug("Got request from shard, waiting for all remembered entities to arrive")
          context.become(waitingForAllEntityIds(gotKeys, ids, Some(sender())))
        }
      case _ =>
        // if we get a write while waiting for the listing, defer it until we saw listing, if not we can get a mismatch
        // of remembered with what the shard thinks it just wrote
        stash()
    }
  }

  private def onUpdate(update: RememberEntitiesShardStore.Update): Unit = {
    val allEvts: Set[Evt] = (update.started.map(Started(_): Evt).union(update.stopped.map(Stopped(_))))
    // map from set of evts (for same ddata key) to one update that applies each of them
    val ddataUpdates: Map[Set[Evt], (Update[ORSet[EntityId]], Int)] =
      allEvts.groupBy(evt => key(evt.id)).map {
        case (key, evts) =>
          (evts, (Update(key, ORSet.empty[EntityId], writeMajority, Some(evts)) { existing =>
            evts.foldLeft(existing) {
              case (acc, Started(id)) => acc :+ id
              case (acc, Stopped(id)) => acc.remove(id)
            }
          }, maxUpdateAttempts))
      }

    ddataUpdates.foreach {
      case (_, (update, _)) =>
        replicator ! update
    }

    context.become(waitingForUpdates(sender(), update, ddataUpdates))
  }

  private def waitingForUpdates(
      requestor: ActorRef,
      update: RememberEntitiesShardStore.Update,
      allUpdates: Map[Set[Evt], (Update[ORSet[EntityId]], Int)]): Receive = {

    // updatesLeft used both to keep track of what work remains and for retrying on timeout up to a limit
    def next(updatesLeft: Map[Set[Evt], (Update[ORSet[EntityId]], Int)]): Receive = {
      case UpdateSuccess(_, Some(evts: Set[Evt] @unchecked)) =>
        log.debug("The DDataShard state was successfully updated for [{}]", evts)
        val remainingAfterThis = updatesLeft - evts
        if (remainingAfterThis.isEmpty) {
          requestor ! RememberEntitiesShardStore.UpdateDone(update.started, update.stopped)
          context.become(idle)
        } else {
          context.become(next(remainingAfterThis))
        }

      case UpdateTimeout(_, Some(evts: Set[Evt] @unchecked)) =>
        val (updateForEvts, retriesLeft) = updatesLeft(evts)
        if (retriesLeft > 0) {
          log.debug("Retrying update because of write timeout, tries left [{}]", retriesLeft)
          replicator ! updateForEvts
          context.become(next(updatesLeft.updated(evts, (updateForEvts, retriesLeft - 1))))
        } else {
          log.error(
            "Unable to update state, within 'updating-state-timeout'= [{}], gave up after [{}] retries",
            writeMajority.timeout.pretty,
            maxUpdateAttempts)
          // will trigger shard restart
          context.stop(self)
        }
      case StoreFailure(_, _) =>
        log.error("Unable to update state, due to store failure")
        // will trigger shard restart
        context.stop(self)
      case ModifyFailure(_, error, cause, _) =>
        log.error(cause, "Unable to update state, due to modify failure: {}", error)
        // will trigger shard restart
        context.stop(self)
      case UpdateDataDeleted(_, _) =>
        log.error("Unable to update state, due to delete")
        // will trigger shard restart
        context.stop(self)
      case update: RememberEntitiesShardStore.Update =>
        log.warning("Got a new update before write of previous completed, dropping update: [{}]", update)
    }

    next(allUpdates)
  }

  private def loadAllEntities(): Unit = {
    (0 until numberOfKeys).toSet[Int].foreach { i =>
      val key = keys(i)
      replicator ! Get(key, readMajority, Some(i))
    }
  }

}
