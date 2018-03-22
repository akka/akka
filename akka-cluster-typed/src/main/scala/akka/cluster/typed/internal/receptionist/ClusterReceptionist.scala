/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.typed.internal.receptionist

import akka.actor.typed.internal.receptionist.{ AbstractServiceKey, ReceptionistBehaviorProvider, ReceptionistMessages }
import akka.actor.typed.receptionist.Receptionist.Command
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.actor.typed.{ ActorRef, Behavior, Terminated }
import akka.actor.{ Address, ExtendedActorSystem }
import akka.annotation.InternalApi
import akka.cluster.ClusterEvent.MemberRemoved
import akka.cluster.ddata.{ DistributedData, ORMultiMap, ORMultiMapKey, Replicator }
import akka.cluster.{ Cluster, ClusterEvent }
import akka.util.TypedMultiMap

import scala.language.existentials
import akka.actor.typed.scaladsl.adapter._

/** INTERNAL API */
@InternalApi
private[typed] object ClusterReceptionist extends ReceptionistBehaviorProvider {

  type SubscriptionsKV[K <: AbstractServiceKey] = ActorRef[ReceptionistMessages.Listing[K#Protocol]]
  type SubscriptionRegistry = TypedMultiMap[AbstractServiceKey, SubscriptionsKV]

  private final val ReceptionistKey = ORMultiMapKey[ServiceKey[_], ActorRef[_]]("ReceptionistKey")
  private final val EmptyORMultiMap = ORMultiMap.empty[ServiceKey[_], ActorRef[_]]

  case class ServiceRegistry(map: ORMultiMap[ServiceKey[_], ActorRef[_]]) extends AnyVal {

    // let's hide all the ugly casts we can in here
    def getOrElse[T](key: AbstractServiceKey, default: ⇒ Set[ActorRef[_]]): Set[ActorRef[key.Protocol]] =
      map.getOrElse(key.asServiceKey, default.asInstanceOf[Set[ActorRef[_]]]).asInstanceOf[Set[ActorRef[key.Protocol]]]

    def getOrEmpty[T](key: AbstractServiceKey): Set[ActorRef[key.Protocol]] = getOrElse(key, Set.empty)

    def addBinding[T](key: ServiceKey[T], value: ActorRef[T])(implicit cluster: Cluster): ServiceRegistry =
      ServiceRegistry(map.addBinding(key, value))

    def removeBinding[T](key: ServiceKey[T], value: ActorRef[T])(implicit cluster: Cluster): ServiceRegistry =
      ServiceRegistry(map.removeBinding(key, value))

    def removeAll(removals: Map[AbstractServiceKey, Set[ActorRef[_]]])(implicit cluster: Cluster): ServiceRegistry = {
      removals.foldLeft(this) {
        case (acc, (key, actors)) ⇒
          actors.foldLeft(acc) {
            case (innerAcc, actor) ⇒
              innerAcc.removeBinding[key.Protocol](key.asServiceKey, actor.asInstanceOf[ActorRef[key.Protocol]])
          }
      }
    }

    def toORMultiMap: ORMultiMap[ServiceKey[_], ActorRef[_]] = map
  }
  object ServiceRegistry {
    val empty = ServiceRegistry(EmptyORMultiMap)

    def collectChangedKeys(previousState: ServiceRegistry, newState: ServiceRegistry): Set[AbstractServiceKey] = {
      val allKeys = previousState.toORMultiMap.entries.keySet ++ newState.toORMultiMap.entries.keySet
      allKeys.foldLeft(Set.empty[AbstractServiceKey]) { (acc, key) ⇒
        val oldValues = previousState.getOrEmpty(key)
        val newValues = newState.getOrEmpty(key)
        if (oldValues != newValues) acc + key
        else acc
      }
    }
  }

  sealed trait InternalCommand
  final case class RegisteredActorTerminated[T](key: ServiceKey[T], ref: ActorRef[T]) extends InternalCommand
  final case class SubscriberTerminated[T](key: ServiceKey[T], ref: ActorRef[ReceptionistMessages.Listing[T]]) extends InternalCommand
  final case class NodeRemoved(addresses: Address) extends InternalCommand
  final case class ChangeFromReplicator(value: ORMultiMap[ServiceKey[_], ActorRef[_]]) extends InternalCommand
  case object RemoveTick extends InternalCommand

  // captures setup/dependencies so we can avoid doing it over and over again
  private class Setup(ctx: ActorContext[Any]) {
    val untypedSystem = ctx.system.toUntyped
    val settings = ClusterReceptionistSettings(ctx.system)
    val replicator = DistributedData(untypedSystem).replicator
    implicit val cluster = Cluster(untypedSystem)
  }

  override def behavior: Behavior[Command] = Behaviors.setup[Any] { ctx ⇒

    val setup = new Setup(ctx)

    // subscribe to changes from other nodes
    val replicatorMessageAdapter: ActorRef[Replicator.ReplicatorMessage] =
      ctx.messageAdapter[Replicator.ReplicatorMessage] {
        case changed @ Replicator.Changed(ReceptionistKey) ⇒ ChangeFromReplicator(changed.get(ReceptionistKey))
      }
    setup.replicator ! Replicator.Subscribe(ReceptionistKey, replicatorMessageAdapter.toUntyped)

    // remove entries when members are removed
    val clusterEventMessageAdapter: ActorRef[MemberRemoved] =
      ctx.messageAdapter[MemberRemoved] { case MemberRemoved(member, _) ⇒ NodeRemoved(member.address) }
    setup.cluster.subscribe(clusterEventMessageAdapter.toUntyped, ClusterEvent.InitialStateAsEvents, classOf[MemberRemoved])

    // also periodic cleanup in case removal from ORMultiMap is skipped due to concurrent update,
    // which is possible for OR CRDTs - done with an adapter to leverage the existing NodesRemoved message
    ctx.system.scheduler.schedule(setup.settings.pruningInterval, setup.settings.pruningInterval,
      ctx.self.toUntyped, RemoveTick)(ctx.system.executionContext)

    behavior(
      setup,
      ServiceRegistry.empty,
      TypedMultiMap.empty[AbstractServiceKey, SubscriptionsKV]
    )
  }.narrow[Command]

  /**
   * @param state The last seen state from the replicator - only updated when we get an update from th replicator
   * @param subscriptions Locally subscriptions, not replicated
   */
  def behavior(
    setup:         Setup,
    state:         ServiceRegistry,
    subscriptions: SubscriptionRegistry): Behavior[Any] =
    Behaviors.setup[Any] { ctx ⇒
      import setup._

      // Helper to create new behavior
      def next(
        newState:         ServiceRegistry      = state,
        newSubscriptions: SubscriptionRegistry = subscriptions) =
        behavior(setup, newState, newSubscriptions)

      /*
       * Hack to allow multiple termination notifications per target
       * FIXME: replace by simple map in our state
       */
      def watchWith(ctx: ActorContext[Any], target: ActorRef[_], msg: InternalCommand): Unit =
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
        val msg = ReceptionistMessages.Listing(key.asServiceKey, state.getOrEmpty(key))
        subscriptions.get(key).foreach(_ ! msg)
      }

      def nodesRemoved(addresses: Set[Address]): Behavior[Any] = {
        // ok to update from several nodes but more efficient to try to do it from one node
        if (cluster.state.leader.contains(cluster.selfAddress) && addresses.nonEmpty) {
          import akka.actor.typed.scaladsl.adapter._
          val localAddress = ctx.system.toUntyped.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress

          def isOnRemovedNode(ref: ActorRef[_]): Boolean = {
            if (ref.path.address.hasLocalScope) addresses(localAddress)
            else addresses(ref.path.address)
          }

          val removals = {
            state.map.entries.foldLeft(Map.empty[AbstractServiceKey, Set[ActorRef[_]]]) {
              case (acc, (key, values)) ⇒
                val removedActors = values.filter(isOnRemovedNode)
                if (removedActors.isEmpty) acc // no change
                else acc + (key -> removedActors)
            }
          }

          if (removals.nonEmpty) {
            if (ctx.log.isDebugEnabled)
              ctx.log.debug(
                "Node(s) [{}] removed, updating registry [{}]",
                addresses.mkString(","),
                removals.map { case (key, actors) ⇒ key.asServiceKey.id -> actors.mkString("[", ", ", "]") }.mkString(","))

            replicator ! Replicator.Update(ReceptionistKey, EmptyORMultiMap, settings.writeConsistency) { registry ⇒
              ServiceRegistry(registry).removeAll(removals).toORMultiMap
            }
          }
          Behaviors.same

        } else Behaviors.same
      }

      def onCommand(cmd: Command): Behavior[Any] = cmd match {
        case ReceptionistMessages.Register(key, serviceInstance, maybeReplyTo) ⇒
          ctx.log.debug("Actor was registered: [{}] [{}]", key, serviceInstance.path)
          watchWith(ctx, serviceInstance, RegisteredActorTerminated(key, serviceInstance))
          maybeReplyTo match {
            case Some(replyTo) ⇒ replyTo ! ReceptionistMessages.Registered(key, serviceInstance)
            case None          ⇒
          }
          replicator ! Replicator.Update(ReceptionistKey, EmptyORMultiMap, settings.writeConsistency) { registry ⇒
            ServiceRegistry(registry).addBinding(key, serviceInstance).toORMultiMap
          }
          Behaviors.same

        case ReceptionistMessages.Find(key, replyTo) ⇒
          replyTo ! ReceptionistMessages.Listing(key.asServiceKey, state.getOrEmpty(key))
          Behaviors.same

        case ReceptionistMessages.Subscribe(key, subscriber) ⇒
          watchWith(ctx, subscriber, SubscriberTerminated(key, subscriber))

          // immediately reply with initial listings to the new subscriber
          subscriber ! ReceptionistMessages.Listing(key.asServiceKey, state.getOrEmpty(key))

          next(newSubscriptions = subscriptions.inserted(key)(subscriber))
      }

      def onInternalCommand(cmd: InternalCommand): Behavior[Any] = cmd match {

        case SubscriberTerminated(key, subscriber) ⇒
          next(newSubscriptions = subscriptions.removed(key)(subscriber))

        case RegisteredActorTerminated(key, serviceInstance) ⇒
          ctx.log.debug("Registered actor terminated: [{}] [{}]", key.asServiceKey.id, serviceInstance.path)
          replicator ! Replicator.Update(ReceptionistKey, EmptyORMultiMap, settings.writeConsistency) { registry ⇒
            ServiceRegistry(registry).removeBinding(key, serviceInstance).toORMultiMap
          }
          Behaviors.same

        case ChangeFromReplicator(value) ⇒
          // every change will come back this way - this is where the local notifications happens
          val newState = ServiceRegistry(value)
          val changedKeys = ServiceRegistry.collectChangedKeys(state, newState)
          if (changedKeys.nonEmpty) {
            if (ctx.log.isDebugEnabled) {
              ctx.log.debug(
                "Registration changed: [{}]",
                changedKeys.map(key ⇒
                  key.asServiceKey.id -> newState.getOrEmpty(key).map(_.path).mkString("[", ", ", "]")
                ).mkString(", "))
            }
            changedKeys.foreach(notifySubscribersFor(_, newState))
            next(newState)
          } else {
            Behaviors.same
          }

        case NodeRemoved(address) ⇒
          // ok to update from several nodes but more efficient to try to do it from one node
          if (cluster.state.leader.contains(cluster.selfAddress)) {
            nodesRemoved(Set(address))
          } else Behaviors.same

        case RemoveTick ⇒
          // ok to update from several nodes but more efficient to try to do it from one node
          if (cluster.state.leader.contains(cluster.selfAddress)) {
            val allAddressesInState: Set[Address] = state.map.entries.flatMap {
              case (_, values) ⇒
                // don't care about local (empty host:port addresses)
                values.collect { case ref if ref.path.address.hasGlobalScope ⇒ ref.path.address }
            }(collection.breakOut)
            val clusterAddresses = cluster.state.members.map(_.address)
            val diff = allAddressesInState diff clusterAddresses
            if (diff.isEmpty) Behavior.same
            else nodesRemoved(diff)
          } else
            Behavior.same
      }

      Behaviors.receive[Any] { (ctx, msg) ⇒
        msg match {
          // support two heterogenous types of messages without union types
          case cmd: Command         ⇒ onCommand(cmd)
          case cmd: InternalCommand ⇒ onInternalCommand(cmd)
          case _                    ⇒ Behaviors.unhandled
        }
      }
    }
}
