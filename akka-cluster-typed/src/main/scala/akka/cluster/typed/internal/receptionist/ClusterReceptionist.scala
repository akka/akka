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
import akka.cluster.ddata.{ ORMultiMap, ORMultiMapKey, Replicator }
import akka.cluster.{ Cluster, ClusterEvent, UniqueAddress }
import akka.remote.AddressUidExtension
import akka.util.TypedMultiMap
import scala.concurrent.duration._

import akka.actor.Address
import akka.cluster.ClusterEvent.ClusterDomainEvent
import akka.cluster.ClusterEvent.ClusterShuttingDown
import akka.cluster.ClusterEvent.MemberJoined
import akka.cluster.ClusterEvent.MemberUp
import akka.cluster.ClusterEvent.MemberWeaklyUp
import akka.cluster.ddata.SelfUniqueAddress

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

  override val name = "clusterReceptionist"

  // values contain system uid to make it possible to discern actors at the same
  // path in different incarnations of a cluster node
  final case class Entry(ref: ActorRef[_], systemUid: Long) {
    def uniqueAddress(selfAddress: Address): UniqueAddress =
      if (ref.path.address.hasLocalScope) UniqueAddress(selfAddress, systemUid)
      else UniqueAddress(ref.path.address, systemUid)
    override def toString: String =
      s"${ref.path.toString}#${ref.path.uid} @ $systemUid"

  }

  private sealed trait InternalCommand extends Command
  private final case class RegisteredActorTerminated[T](key: ServiceKey[T], ref: ActorRef[T]) extends InternalCommand
  private final case class SubscriberTerminated[T](key: ServiceKey[T], ref: ActorRef[ReceptionistMessages.Listing[T]])
      extends InternalCommand
  private final case class NodeAdded(addresses: UniqueAddress) extends InternalCommand
  private final case class NodeRemoved(addresses: UniqueAddress) extends InternalCommand
  private final case class ChangeFromReplicator(key: DDataKey, value: ORMultiMap[ServiceKey[_], Entry])
      extends InternalCommand
  private case object RemoveTick extends InternalCommand
  private case object PruneTombstonesTick extends InternalCommand

  // captures setup/dependencies so we can avoid doing it over and over again
  final class Setup(ctx: ActorContext[Command]) {
    val untypedSystem = ctx.system.toUntyped
    val settings = ClusterReceptionistSettings(ctx.system)
    val selfSystemUid = AddressUidExtension(untypedSystem).longAddressUid
    lazy val keepTombstonesFor = cluster.settings.PruneGossipTombstonesAfter match {
      case f: FiniteDuration => f
      case _                 => throw new IllegalStateException("Cannot actually happen")
    }
    val cluster = Cluster(untypedSystem)
    // don't use DistributedData.selfUniqueAddress here, because that will initialize extension, which
    // isn't used otherwise by the ClusterReceptionist
    implicit val selfNodeAddress = SelfUniqueAddress(cluster.selfUniqueAddress)

    val replicator = ctx.actorOf(Replicator.props(settings.replicatorSettings), "replicator")

    def newTombstoneDeadline() = Deadline(keepTombstonesFor)
    def selfUniqueAddress: UniqueAddress = cluster.selfUniqueAddress
  }

  override def behavior: Behavior[Command] =
    Behaviors.setup { ctx =>
      ctx.setLoggerClass(classOf[ClusterReceptionist])
      Behaviors.withTimers { timers =>
        val setup = new Setup(ctx)
        // include selfUniqueAddress so that it can be used locally before joining cluster
        val registry = ShardedServiceRegistry(setup.settings.distributedKeyCount).addNode(setup.selfUniqueAddress)

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

        // keep track of cluster members
        // remove entries when members are removed
        val clusterEventMessageAdapter: ActorRef[ClusterDomainEvent] =
          ctx.messageAdapter[ClusterDomainEvent] {
            case MemberJoined(member)     => NodeAdded(member.uniqueAddress)
            case MemberWeaklyUp(member)   => NodeAdded(member.uniqueAddress)
            case MemberUp(member)         => NodeAdded(member.uniqueAddress)
            case MemberRemoved(member, _) => NodeRemoved(member.uniqueAddress)
            case ClusterShuttingDown      => NodeRemoved(setup.cluster.selfUniqueAddress)
            case other =>
              throw new IllegalStateException(s"Unexpected ClusterDomainEvent $other. Please report bug.")
          }
        setup.cluster.subscribe(
          clusterEventMessageAdapter.toUntyped,
          ClusterEvent.InitialStateAsEvents,
          classOf[MemberJoined],
          classOf[MemberWeaklyUp],
          classOf[MemberUp],
          classOf[MemberRemoved],
          ClusterShuttingDown.getClass)

        // also periodic cleanup in case removal from ORMultiMap is skipped due to concurrent update,
        // which is possible for OR CRDTs - done with an adapter to leverage the existing NodesRemoved message
        timers.startTimerWithFixedDelay("remove-nodes", RemoveTick, setup.settings.pruningInterval)

        // default tomstone keepalive is 24h (based on prune-gossip-tombstones-after) and keeping the actorrefs
        // around isn't very costly so don't prune often
        timers.startTimerWithFixedDelay("prune-tombstones", PruneTombstonesTick, setup.keepTombstonesFor / 24)

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
      def watchWith(ctx: ActorContext[Command], target: ActorRef[_], msg: InternalCommand): Unit = {
        ctx.spawnAnonymous[Nothing](Behaviors.setup[Nothing] { innerCtx =>
          innerCtx.watch(target)
          Behaviors.receiveSignal[Nothing] {
            case (_, Terminated(`target`)) =>
              ctx.self ! msg
              Behaviors.stopped
          }
        })
        ()
      }

      def isLeader = {
        cluster.state.leader.contains(cluster.selfAddress)
      }

      def nodesRemoved(addresses: Set[UniqueAddress]): Unit = {
        // ok to update from several nodes but more efficient to try to do it from one node
        if (isLeader) {
          def isOnRemovedNode(entry: Entry): Boolean = addresses(entry.uniqueAddress(setup.selfUniqueAddress.address))

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
                "ClusterReceptionist [{}] - Node(s) removed [{}], updating registry removing entries: [{}]",
                cluster.selfAddress,
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
      }

      def onCommand(cmd: Command): Behavior[Command] = cmd match {
        case ReceptionistMessages.Register(key, serviceInstance, maybeReplyTo) =>
          if (serviceInstance.path.address.hasLocalScope) {
            val entry = Entry(serviceInstance, setup.selfSystemUid)
            ctx.log.debug("ClusterReceptionist [{}] - Actor was registered: [{}] [{}]", cluster.selfAddress, key, entry)
            watchWith(ctx, serviceInstance, RegisteredActorTerminated(key, serviceInstance))
            maybeReplyTo match {
              case Some(replyTo) => replyTo ! ReceptionistMessages.Registered(key, serviceInstance)
              case None          =>
            }
            val ddataKey = registry.ddataKeyFor(key)
            replicator ! Replicator.Update(ddataKey, EmptyORMultiMap, settings.writeConsistency) { registry =>
              ServiceRegistry(registry).addBinding(key, entry).toORMultiMap
            }
          } else {
            ctx.log.error(s"ClusterReceptionist [{}] - Register of non-local [{}] is not supported", serviceInstance)
          }
          Behaviors.same

        case ReceptionistMessages.Find(key, replyTo) =>
          replyTo ! ReceptionistMessages.Listing(key.asServiceKey, registry.activeActorRefsFor(key, selfUniqueAddress))
          Behaviors.same

        case ReceptionistMessages.Subscribe(key, subscriber) =>
          watchWith(ctx, subscriber, SubscriberTerminated(key, subscriber))

          // immediately reply with initial listings to the new subscriber
          val listing =
            ReceptionistMessages.Listing(key.asServiceKey, registry.activeActorRefsFor(key, selfUniqueAddress))
          subscriber ! listing

          next(newSubscriptions = subscriptions.inserted(key)(subscriber))
      }

      def onInternalCommand(cmd: InternalCommand): Behavior[Command] = cmd match {

        case SubscriberTerminated(key, subscriber) =>
          next(newSubscriptions = subscriptions.removed(key)(subscriber))

        case RegisteredActorTerminated(key, serviceInstance) =>
          val entry = Entry(serviceInstance, setup.selfSystemUid)
          ctx.log.debug(
            "ClusterReceptionist [{}] - Registered actor terminated: [{}] [{}]",
            cluster.selfAddress,
            key.asServiceKey.id,
            entry)
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
                "ClusterReceptionist [{}] - Change from replicator: [{}], changes: [{}], tombstones [{}]",
                cluster.selfAddress,
                newState.entries.entries,
                changedKeys
                  .map(key => key.asServiceKey.id -> newState.entriesFor(key).mkString("[", ", ", "]"))
                  .mkString(", "),
                newRegistry.tombstones.mkString(", "))
            }

            changedKeys.foreach { changedKey =>
              val serviceKey = changedKey.asServiceKey

              val subscribers = subscriptions.get(changedKey)
              if (subscribers.nonEmpty) {
                val listing =
                  ReceptionistMessages
                    .Listing(serviceKey, newRegistry.activeActorRefsFor(serviceKey, selfUniqueAddress))
                subscribers.foreach(_ ! listing)
              }

              // because of how ORMultiMap/ORset works, we could have a case where an actor we removed
              // is re-introduced because of a concurrent update, in that case we need to re-remove it
              val tombstonedButReAdded = newRegistry.actorRefsFor(serviceKey).filter(newRegistry.hasTombstone)
              if (tombstonedButReAdded.nonEmpty) {
                if (ctx.log.isDebugEnabled)
                  ctx.log.debug(
                    "ClusterReceptionist [{}] - Saw ActorRefs that were tomstoned [{}], re-removing.",
                    cluster.selfAddress,
                    tombstonedButReAdded.mkString(", "))

                replicator ! Replicator.Update(ddataKey, EmptyORMultiMap, settings.writeConsistency) { registry =>
                  tombstonedButReAdded
                    .foldLeft(ServiceRegistry(registry)) { (acc, ref) =>
                      acc.removeBinding(serviceKey, Entry(ref, setup.selfSystemUid))
                    }
                    .toORMultiMap
                }
              }
            }

            next(newRegistry)
          } else {
            Behaviors.same
          }

        case NodeAdded(uniqueAddress) =>
          next(registry.addNode(uniqueAddress))

        case NodeRemoved(uniqueAddress) =>
          if (uniqueAddress == selfUniqueAddress) {
            ctx.log.debug("ClusterReceptionist [{}] - terminated/removed", cluster.selfAddress)
            // If self cluster node is shutting down our own entries should have been removed via
            // watch-Terminated or will be removed by other nodes. This point is anyway too late.
            Behaviors.stopped
          } else {
            // Ok to update from several nodes but more efficient to try to do it from one node.
            if (isLeader) {
              ctx.log.debug(
                "ClusterReceptionist [{}] - Leader node observed removed node [{}]",
                cluster.selfAddress,
                uniqueAddress)
              nodesRemoved(Set(uniqueAddress))
            }

            next(registry.removeNode(uniqueAddress))
          }

        case RemoveTick =>
          // ok to update from several nodes but more efficient to try to do it from one node
          if (isLeader) {
            val allAddressesInState: Set[UniqueAddress] = registry.allUniqueAddressesInState(setup.selfUniqueAddress)
            val notInCluster = allAddressesInState.diff(registry.nodes)

            if (notInCluster.isEmpty) Behaviors.same
            else {
              if (ctx.log.isDebugEnabled)
                ctx.log.debug(
                  "ClusterReceptionist [{}] - Leader node cleanup tick, removed nodes: [{}]",
                  cluster.selfAddress,
                  notInCluster.mkString(","))
              nodesRemoved(notInCluster)
            }
          }
          Behaviors.same

        case PruneTombstonesTick =>
          val prunedRegistry = registry.pruneTombstones()
          if (prunedRegistry eq registry) Behaviors.same
          else {
            ctx.log.debug("ClusterReceptionist [{}] - Pruning tombstones", cluster.selfAddress)
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
