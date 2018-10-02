/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
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
import akka.cluster.ddata.{ DistributedData, ORMultiMap, ORMultiMapKey, Replicator }
import akka.cluster.{ Cluster, ClusterEvent, UniqueAddress }
import akka.remote.AddressUidExtension
import akka.util.TypedMultiMap

import scala.language.existentials

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
  private final case class SubscriberTerminated[T](key: ServiceKey[T], ref: ActorRef[ReceptionistMessages.Listing[T]]) extends InternalCommand
  private final case class NodeRemoved(addresses: UniqueAddress) extends InternalCommand
  private final case class ChangeFromReplicator(
    key:   DDataKey,
    value: ORMultiMap[ServiceKey[_], Entry]) extends InternalCommand
  private case object RemoveTick extends InternalCommand

  // captures setup/dependencies so we can avoid doing it over and over again
  final class Setup(ctx: ActorContext[Command]) {
    val untypedSystem = ctx.system.toUntyped
    val settings = ClusterReceptionistSettings(ctx.system)
    val replicator = DistributedData(untypedSystem).replicator
    val selfSystemUid = AddressUidExtension(untypedSystem).longAddressUid
    implicit val cluster = Cluster(untypedSystem)
    def selfUniqueAddress: UniqueAddress = cluster.selfUniqueAddress
  }

  override def behavior: Behavior[Command] = Behaviors.setup { ctx ⇒

    val setup = new Setup(ctx)
    val registry = ShardedServiceRegistry(setup.settings.distributedKeyCount)

    // subscribe to changes from other nodes
    val replicatorMessageAdapter: ActorRef[Replicator.ReplicatorMessage] =
      ctx.messageAdapter[Replicator.ReplicatorMessage] {
        case changed: Replicator.Changed[_] @unchecked ⇒
          ChangeFromReplicator(
            changed.key.asInstanceOf[DDataKey],
            changed.dataValue.asInstanceOf[ORMultiMap[ServiceKey[_], Entry]])
      }

    registry.allDdataKeys.foreach(key ⇒
      setup.replicator ! Replicator.Subscribe(key, replicatorMessageAdapter.toUntyped)
    )

    // remove entries when members are removed
    val clusterEventMessageAdapter: ActorRef[MemberRemoved] =
      ctx.messageAdapter[MemberRemoved] { case MemberRemoved(member, _) ⇒ NodeRemoved(member.uniqueAddress) }
    setup.cluster.subscribe(clusterEventMessageAdapter.toUntyped, ClusterEvent.InitialStateAsEvents, classOf[MemberRemoved])

    // also periodic cleanup in case removal from ORMultiMap is skipped due to concurrent update,
    // which is possible for OR CRDTs - done with an adapter to leverage the existing NodesRemoved message
    ctx.system.scheduler.schedule(setup.settings.pruningInterval, setup.settings.pruningInterval,
      ctx.self.toUntyped, RemoveTick)(ctx.system.executionContext)

    behavior(
      setup,
      registry,
      TypedMultiMap.empty[AbstractServiceKey, SubscriptionsKV]
    )
  }

  /**
   * @param registry The last seen state from the replicator - only updated when we get an update from th replicator
   * @param subscriptions Locally subscriptions, not replicated
   */
  def behavior(
    setup:         Setup,
    registry:      ShardedServiceRegistry,
    subscriptions: SubscriptionRegistry): Behavior[Command] =
    Behaviors.setup { ctx ⇒
      import setup._

      // Helper to create new behavior
      def next(
        newState:         ShardedServiceRegistry = registry,
        newSubscriptions: SubscriptionRegistry   = subscriptions) =
        behavior(setup, newState, newSubscriptions)

      /*
       * Hack to allow multiple termination notifications per target
       * FIXME: replace by simple map in our state
       */
      def watchWith(ctx: ActorContext[Command], target: ActorRef[_], msg: InternalCommand): Unit =
        ctx.spawnAnonymous[Nothing](Behaviors.setup[Nothing] { innerCtx ⇒
          innerCtx.watch(target)
          Behaviors.receive[Nothing]((_, _) ⇒ Behaviors.same)
            .receiveSignal {
              case (_, Terminated(`target`)) ⇒
                ctx.self ! msg
                Behaviors.stopped
            }
        })

      def notifySubscribersFor(key: AbstractServiceKey, state: ServiceRegistry): Unit = {
        val msg = ReceptionistMessages.Listing(key.asServiceKey, state.actorRefsFor(key))
        subscriptions.get(key).foreach(_ ! msg)
      }

      def nodesRemoved(addresses: Set[UniqueAddress]): Behavior[Command] = {
        // ok to update from several nodes but more efficient to try to do it from one node
        if (cluster.state.leader.contains(cluster.selfAddress) && addresses.nonEmpty) {
          def isOnRemovedNode(entry: Entry): Boolean = addresses(entry.uniqueAddress(setup.selfUniqueAddress))
          val removals = {
            registry.allServices.foldLeft(Map.empty[AbstractServiceKey, Set[Entry]]) {
              case (acc, (key, entries)) ⇒
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
                removals.map {
                  case (key, entries) ⇒ key.asServiceKey.id -> entries.mkString("[", ", ", "]")
                }.mkString(","))

            // shard changes over the ddata keys they belong to
            val removalsPerDdataKey = registry.entriesPerDdataKey(removals)

            removalsPerDdataKey.foreach {
              case (ddataKey, removalForKey) ⇒
                replicator ! Replicator.Update(ddataKey, EmptyORMultiMap, settings.writeConsistency) { registry ⇒
                  ServiceRegistry(registry).removeAll(removalForKey).toORMultiMap
                }
            }
          }
          Behaviors.same

        } else Behaviors.same
      }

      def onCommand(cmd: Command): Behavior[Command] = cmd match {
        case ReceptionistMessages.Register(key, serviceInstance, maybeReplyTo) ⇒
          val entry = Entry(serviceInstance, setup.selfSystemUid)
          ctx.log.debug("Actor was registered: [{}] [{}]", key, entry)
          watchWith(ctx, serviceInstance, RegisteredActorTerminated(key, serviceInstance))
          maybeReplyTo match {
            case Some(replyTo) ⇒ replyTo ! ReceptionistMessages.Registered(key, serviceInstance)
            case None          ⇒
          }
          val ddataKey = registry.ddataKeyFor(key)
          replicator ! Replicator.Update(ddataKey, EmptyORMultiMap, settings.writeConsistency) { registry ⇒
            ServiceRegistry(registry).addBinding(key, entry).toORMultiMap
          }
          Behaviors.same

        case ReceptionistMessages.Find(key, replyTo) ⇒
          replyTo ! ReceptionistMessages.Listing(key.asServiceKey, registry.actorRefsFor(key))
          Behaviors.same

        case ReceptionistMessages.Subscribe(key, subscriber) ⇒
          watchWith(ctx, subscriber, SubscriberTerminated(key, subscriber))

          // immediately reply with initial listings to the new subscriber
          subscriber ! ReceptionistMessages.Listing(key.asServiceKey, registry.actorRefsFor(key))

          next(newSubscriptions = subscriptions.inserted(key)(subscriber))
      }

      def onInternalCommand(cmd: InternalCommand): Behavior[Command] = cmd match {

        case SubscriberTerminated(key, subscriber) ⇒
          next(newSubscriptions = subscriptions.removed(key)(subscriber))

        case RegisteredActorTerminated(key, serviceInstance) ⇒
          val entry = Entry(serviceInstance, setup.selfSystemUid)
          ctx.log.debug("Registered actor terminated: [{}] [{}]", key.asServiceKey.id, entry)
          val ddataKey = registry.ddataKeyFor(key)
          replicator ! Replicator.Update(ddataKey, EmptyORMultiMap, settings.writeConsistency) { registry ⇒
            ServiceRegistry(registry).removeBinding(key, entry).toORMultiMap
          }
          Behaviors.same

        case ChangeFromReplicator(ddataKey, value) ⇒
          // every change will come back this way - this is where the local notifications happens
          val newState = ServiceRegistry(value)
          val changedKeys = registry.collectChangedKeys(ddataKey, newState)
          val newRegistry = registry.withServiceRegistry(ddataKey, newState)
          if (changedKeys.nonEmpty) {
            if (ctx.log.isDebugEnabled) {
              ctx.log.debug(
                "Change from replicator: [{}], changes: [{}]",
                newState.entries.entries,
                changedKeys.map(key ⇒
                  key.asServiceKey.id -> newState.entriesFor(key).mkString("[", ", ", "]")
                ).mkString(", "))
            }
            changedKeys.foreach(notifySubscribersFor(_, newState))
            next(newRegistry)
          } else {
            Behaviors.same
          }

        case NodeRemoved(uniqueAddress) ⇒
          // ok to update from several nodes but more efficient to try to do it from one node
          if (cluster.state.leader.contains(cluster.selfAddress)) {
            ctx.log.debug(s"Leader node observed removed address [{}]", uniqueAddress)
            nodesRemoved(Set(uniqueAddress))
          } else Behaviors.same

        case RemoveTick ⇒
          // ok to update from several nodes but more efficient to try to do it from one node
          if (cluster.state.leader.contains(cluster.selfAddress)) {
            val allAddressesInState: Set[UniqueAddress] = registry.allUniqueAddressesInState(setup.selfUniqueAddress)
            val clusterAddresses = cluster.state.members.map(_.uniqueAddress)
            val notInCluster = allAddressesInState diff clusterAddresses

            if (notInCluster.isEmpty) Behavior.same
            else {
              if (ctx.log.isDebugEnabled)
                ctx.log.debug("Leader node cleanup tick, removed nodes: [{}]", notInCluster.mkString(","))
              nodesRemoved(notInCluster)
            }
          } else
            Behavior.same
      }

      Behaviors.receive[Command] { (ctx, msg) ⇒
        msg match {
          // support two heterogenous types of messages without union types
          case cmd: InternalCommand ⇒ onInternalCommand(cmd)
          case cmd: Command         ⇒ onCommand(cmd)
          case _                    ⇒ Behaviors.unhandled
        }
      }
    }
}
