/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.typed.internal.receptionist

import akka.actor.typed.internal.receptionist.{ AbstractServiceKey, ReceptionistBehaviorProvider, ReceptionistMessages }
import akka.actor.typed.receptionist.Receptionist.Command
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.actor.typed.{ ActorRef, Behavior, Terminated }
import akka.annotation.InternalApi
import akka.cluster.ClusterEvent.MemberRemoved
import akka.cluster.ddata.typed.scaladsl.DistributedData
import akka.cluster.{ ddata => dd }
import akka.cluster.ddata.{ ORMultiMap, ORMultiMapKey, Replicator }
import akka.cluster.{ Cluster, ClusterEvent, UniqueAddress }
import akka.remote.AddressUidExtension
import akka.util.TypedMultiMap

import scala.concurrent.duration._

// just to provide a log class
/** INTERNAL API */
@InternalApi
private[typed] final class ClusterReceptionist

/** INTERNAL API */
@InternalApi
private[typed] object ClusterReceptionist extends ReceptionistBehaviorProvider {

  type SubscriptionsKV[K <: AbstractServiceKey] = ActorRef[ReceptionistMessages.Listing[K#Protocol]]
  type SubscriptionRegistry = TypedMultiMap[AbstractServiceKey, SubscriptionsKV]
  type DDataKey = ORMultiMapKey[ServiceKey[_], Entry]

  final val EmptyORMultiMap = ORMultiMap.empty[ServiceKey[_], Entry]

  // values contain system uid to make it possible to discern actors at the same
  // path in different incarnations of a cluster node
  final case class Entry(ref: ActorRef[_], systemUid: Long) {
    def uniqueAddress(selfUniqueAddress: UniqueAddress): UniqueAddress =
      if (ref.path.address.hasLocalScope) selfUniqueAddress
      else UniqueAddress(ref.path.address, systemUid)
    override def toString = ref.path.toString + "#" + ref.path.uid
  }

  private sealed trait InternalCommand extends Command
  private final case class RegisteredActorTerminated[T](key: ServiceKey[T], ref: ActorRef[T]) extends InternalCommand
  private final case class SubscriberTerminated[T](key: ServiceKey[T], ref: ActorRef[ReceptionistMessages.Listing[T]])
      extends InternalCommand
  private final case class NodeRemoved(addresses: UniqueAddress) extends InternalCommand
  private final case class ChangeFromReplicator(key: DDataKey, value: ORMultiMap[ServiceKey[_], Entry])
      extends InternalCommand
  private case object RemoveTick extends InternalCommand
  private case object PruneTombstonesTick extends InternalCommand

  // captures setup/dependencies so we can avoid doing it over and over again
  final class Setup(ctx: ActorContext[Command]) {
    val untypedSystem = ctx.system.toUntyped
    val settings = ClusterReceptionistSettings(ctx.system)
    val replicator = dd.DistributedData(untypedSystem).replicator
    val selfSystemUid = AddressUidExtension(untypedSystem).longAddressUid
    lazy val keepTombstonesFor = cluster.settings.PruneGossipTombstonesAfter match {
      case f: FiniteDuration => f
      case _                 => throw new IllegalStateException("Cannot actually happen")
    }
    val cluster = Cluster(untypedSystem)
    implicit val selfNodeAddress = DistributedData(ctx.system).selfUniqueAddress
    def newTombstoneDeadline() = Deadline(keepTombstonesFor)
    def selfUniqueAddress: UniqueAddress = cluster.selfUniqueAddress
  }

  override def behavior: Behavior[Command] =
    Behaviors.setup { ctx =>
      ctx.setLoggerClass(classOf[ClusterReceptionist])
      Behaviors.withTimers { timers =>
        val setup = new Setup(ctx)
        val registry = ShardedServiceRegistry(setup.settings.distributedKeyCount)

        // subscribe to changes from other nodes
        val replicatorMessageAdapter: ActorRef[Replicator.ReplicatorMessage] =
          ctx.messageAdapter[Replicator.ReplicatorMessage] {
            case changed: Replicator.Changed[_] @unchecked =>
              ChangeFromReplicator(
                changed.key.asInstanceOf[DDataKey],
                changed.dataValue.asInstanceOf[ORMultiMap[ServiceKey[_], Entry]])
          }

        registry.allDdataKeys.foreach(key =>
          setup.replicator ! Replicator.Subscribe(key, replicatorMessageAdapter.toUntyped))

        // remove entries when members are removed
        val clusterEventMessageAdapter: ActorRef[MemberRemoved] =
          ctx.messageAdapter[MemberRemoved] { case MemberRemoved(member, _) => NodeRemoved(member.uniqueAddress) }
        setup.cluster.subscribe(
          clusterEventMessageAdapter.toUntyped,
          ClusterEvent.InitialStateAsEvents,
          classOf[MemberRemoved])

        // also periodic cleanup in case removal from ORMultiMap is skipped due to concurrent update,
        // which is possible for OR CRDTs - done with an adapter to leverage the existing NodesRemoved message
        timers.startPeriodicTimer("remove-nodes", RemoveTick, setup.settings.pruningInterval)

        // default tomstone keepalive is 24h (based on prune-gossip-tombstones-after) and keeping the actorrefs
        // around isn't very costly so don't prune often
        timers.startPeriodicTimer("prune-tombstones", PruneTombstonesTick, setup.keepTombstonesFor / 24)

        behavior(setup, registry, TypedMultiMap.empty[AbstractServiceKey, SubscriptionsKV])
      }
    }

  /**
   * @param registry The last seen state from the replicator - only updated when we get an update from th replicator
   * @param subscriptions Locally subscriptions, not replicated
   */
  def behavior(setup: Setup, registry: ShardedServiceRegistry, subscriptions: SubscriptionRegistry): Behavior[Command] =
    Behaviors.setup { ctx =>
      import setup._

      // Helper to create new behavior
      def next(newState: ShardedServiceRegistry = registry, newSubscriptions: SubscriptionRegistry = subscriptions) =
        behavior(setup, newState, newSubscriptions)

      /*
       * Hack to allow multiple termination notifications per target
       * FIXME #26505: replace by simple map in our state
       */
      def watchWith(ctx: ActorContext[Command], target: ActorRef[_], msg: InternalCommand): Unit =
        ctx.spawnAnonymous[Nothing](Behaviors.setup[Nothing] { innerCtx =>
          innerCtx.watch(target)
          Behaviors.receive[Nothing]((_, _) => Behaviors.same).receiveSignal {
            case (_, Terminated(`target`)) =>
              ctx.self ! msg
              Behaviors.stopped
          }
        })

      def notifySubscribersFor(key: AbstractServiceKey, state: ServiceRegistry): Unit = {
        // filter tombstoned refs to avoid an extra update
        // to subscribers in the case of lost removals (because of how ORMultiMap works)
        val refsForKey = state.actorRefsFor(key)
        val refsWithoutTombstoned =
          if (registry.tombstones.isEmpty) refsForKey
          else refsForKey.filterNot(registry.hasTombstone)
        val msg = ReceptionistMessages.Listing(key.asServiceKey, refsWithoutTombstoned)
        subscriptions.get(key).foreach(_ ! msg)
      }

      def nodesRemoved(addresses: Set[UniqueAddress]): Behavior[Command] = {
        // ok to update from several nodes but more efficient to try to do it from one node
        if (cluster.state.leader.contains(cluster.selfAddress) && addresses.nonEmpty) {
          def isOnRemovedNode(entry: Entry): Boolean = addresses(entry.uniqueAddress(setup.selfUniqueAddress))

          val removals = {
            registry.allServices.foldLeft(Map.empty[AbstractServiceKey, Set[Entry]]) {
              case (acc, (key, entries)) =>
                val removedEntries = entries.filter(isOnRemovedNode)
                if (removedEntries.isEmpty) acc // no change
                else acc + (key -> removedEntries)
            }
          }

          if (removals.nonEmpty) {
            if (ctx.log.isDebugEnabled)
              ctx.log.debug(
                "Node(s) [{}] removed, updating registry removing: [{}]",
                addresses.mkString(","),
                removals
                  .map {
                    case (key, entries) => key.asServiceKey.id -> entries.mkString("[", ", ", "]")
                  }
                  .mkString(","))

            // shard changes over the ddata keys they belong to
            val removalsPerDdataKey = registry.entriesPerDdataKey(removals)

            removalsPerDdataKey.foreach {
              case (ddataKey, removalForKey) =>
                replicator ! Replicator.Update(ddataKey, EmptyORMultiMap, settings.writeConsistency) { registry =>
                  ServiceRegistry(registry).removeAll(removalForKey).toORMultiMap
                }
            }

          }

        }
        Behaviors.same
      }

      def onCommand(cmd: Command): Behavior[Command] = cmd match {
        case ReceptionistMessages.Register(key, serviceInstance, maybeReplyTo) =>
          val entry = Entry(serviceInstance, setup.selfSystemUid)
          ctx.log.debug("Actor was registered: [{}] [{}]", key, entry)
          watchWith(ctx, serviceInstance, RegisteredActorTerminated(key, serviceInstance))
          maybeReplyTo match {
            case Some(replyTo) => replyTo ! ReceptionistMessages.Registered(key, serviceInstance)
            case None          =>
          }
          val ddataKey = registry.ddataKeyFor(key)
          replicator ! Replicator.Update(ddataKey, EmptyORMultiMap, settings.writeConsistency) { registry =>
            ServiceRegistry(registry).addBinding(key, entry).toORMultiMap
          }
          Behaviors.same

        case ReceptionistMessages.Find(key, replyTo) =>
          replyTo ! ReceptionistMessages.Listing(key.asServiceKey, registry.actorRefsFor(key))
          Behaviors.same

        case ReceptionistMessages.Subscribe(key, subscriber) =>
          watchWith(ctx, subscriber, SubscriberTerminated(key, subscriber))

          // immediately reply with initial listings to the new subscriber
          subscriber ! ReceptionistMessages.Listing(key.asServiceKey, registry.actorRefsFor(key))

          next(newSubscriptions = subscriptions.inserted(key)(subscriber))
      }

      def onInternalCommand(cmd: InternalCommand): Behavior[Command] = cmd match {

        case SubscriberTerminated(key, subscriber) =>
          next(newSubscriptions = subscriptions.removed(key)(subscriber))

        case RegisteredActorTerminated(key, serviceInstance) =>
          val entry = Entry(serviceInstance, setup.selfSystemUid)
          ctx.log.debug("Registered actor terminated: [{}] [{}]", key.asServiceKey.id, entry)
          val ddataKey = registry.ddataKeyFor(key)
          replicator ! Replicator.Update(ddataKey, EmptyORMultiMap, settings.writeConsistency) { registry =>
            ServiceRegistry(registry).removeBinding(key, entry).toORMultiMap
          }
          // tombstone removals so they are not re-added by merging with other concurrent
          // registrations for the same key
          next(newState = registry.addTombstone(serviceInstance, setup.newTombstoneDeadline()))

        case ChangeFromReplicator(ddataKey, value) =>
          // every change will come back this way - this is where the local notifications happens
          val newState = ServiceRegistry(value)
          val changedKeys = registry.collectChangedKeys(ddataKey, newState)
          val newRegistry = registry.withServiceRegistry(ddataKey, newState)
          if (changedKeys.nonEmpty) {
            if (ctx.log.isDebugEnabled) {
              ctx.log.debug(
                "Change from replicator: [{}], changes: [{}], tombstones [{}]",
                newState.entries.entries,
                changedKeys
                  .map(key => key.asServiceKey.id -> newState.entriesFor(key).mkString("[", ", ", "]"))
                  .mkString(", "),
                registry.tombstones.mkString(", "))
            }
            changedKeys.foreach { changedKey =>
              notifySubscribersFor(changedKey, newState)

              // because of how ORMultiMap/ORset works, we could have a case where an actor we removed
              // is re-introduced because of a concurrent update, in that case we need to re-remove it
              val serviceKey = changedKey.asServiceKey
              val tombstonedButReAdded =
                newRegistry.actorRefsFor(serviceKey).filter(newRegistry.hasTombstone)
              tombstonedButReAdded.foreach { actorRef =>
                ctx.log.debug("Saw actorref that was tomstoned {}, re-removing.", actorRef)
                replicator ! Replicator.Update(ddataKey, EmptyORMultiMap, settings.writeConsistency) { registry =>
                  ServiceRegistry(registry).removeBinding(serviceKey, Entry(actorRef, setup.selfSystemUid)).toORMultiMap
                }
              }
            }

            next(newRegistry)
          } else {
            Behaviors.same
          }

        case NodeRemoved(uniqueAddress) =>
          // ok to update from several nodes but more efficient to try to do it from one node
          if (cluster.state.leader.contains(cluster.selfAddress)) {
            ctx.log.debug(s"Leader node observed removed address [{}]", uniqueAddress)
            nodesRemoved(Set(uniqueAddress))
          } else Behaviors.same

        case RemoveTick =>
          // ok to update from several nodes but more efficient to try to do it from one node
          if (cluster.state.leader.contains(cluster.selfAddress)) {
            val allAddressesInState: Set[UniqueAddress] = registry.allUniqueAddressesInState(setup.selfUniqueAddress)
            val clusterAddresses = cluster.state.members.map(_.uniqueAddress)
            val notInCluster = allAddressesInState.diff(clusterAddresses)

            if (notInCluster.isEmpty) Behavior.same
            else {
              if (ctx.log.isDebugEnabled)
                ctx.log.debug("Leader node cleanup tick, removed nodes: [{}]", notInCluster.mkString(","))
              nodesRemoved(notInCluster)
            }
          } else
            Behavior.same

        case PruneTombstonesTick =>
          val prunedRegistry = registry.pruneTombstones()
          if (prunedRegistry eq registry) Behaviors.same
          else {
            ctx.log.debug(s"Pruning tombstones")
            next(prunedRegistry)
          }
      }

      Behaviors.receive[Command] { (_, msg) =>
        msg match {
          // support two heterogenous types of messages without union types
          case cmd: InternalCommand => onInternalCommand(cmd)
          case cmd: Command         => onCommand(cmd)
          case _                    => Behaviors.unhandled
        }
      }
    }
}
