/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.typed.internal.receptionist

import scala.concurrent.duration._

import akka.annotation.InternalApi
import akka.cluster.Cluster
import akka.cluster.ddata.DistributedData
import akka.cluster.ddata.ORMultiMap
import akka.cluster.ddata.ORMultiMapKey
import akka.cluster.ddata.Replicator
import akka.cluster.ddata.Replicator.WriteConsistency
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.internal.receptionist.ReceptionistBehaviorProvider
import akka.actor.typed.internal.receptionist.ReceptionistImpl
import akka.actor.typed.internal.receptionist.ReceptionistImpl._
import akka.actor.typed.receptionist.Receptionist.AbstractServiceKey
import akka.actor.typed.receptionist.Receptionist.AllCommands
import akka.actor.typed.receptionist.Receptionist.Command
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.ActorContext
import scala.language.existentials
import scala.language.higherKinds

import akka.actor.typed.ActorSystem
import akka.actor.Address
import akka.cluster.ClusterEvent
import akka.cluster.ClusterEvent.MemberRemoved
import akka.util.Helpers.toRootLowerCase
import com.typesafe.config.Config

/** Internal API */
@InternalApi
private[typed] object ClusterReceptionist extends ReceptionistBehaviorProvider {
  private final val ReceptionistKey = ORMultiMapKey[ServiceKey[_], ActorRef[_]]("ReceptionistKey")
  private final val EmptyORMultiMap = ORMultiMap.empty[ServiceKey[_], ActorRef[_]]

  case class TypedORMultiMap[K[_], V[_]](map: ORMultiMap[K[_], V[_]]) extends AnyVal {
    def getOrElse[T](key: K[T], default: ⇒ Set[V[T]]): Set[V[T]] =
      map.getOrElse(key, default.asInstanceOf[Set[V[_]]]).asInstanceOf[Set[V[T]]]

    def getOrEmpty[T](key: K[T]): Set[V[T]] = getOrElse(key, Set.empty)

    def addBinding[T](key: K[T], value: V[T])(implicit cluster: Cluster): TypedORMultiMap[K, V] =
      TypedORMultiMap[K, V](map.addBinding(key, value))

    def removeBinding[T](key: K[T], value: V[T])(implicit cluster: Cluster): TypedORMultiMap[K, V] =
      TypedORMultiMap[K, V](map.removeBinding(key, value))

    def toORMultiMap: ORMultiMap[K[_], V[_]] = map
  }
  object TypedORMultiMap {
    def empty[K[_], V[_]] = TypedORMultiMap[K, V](ORMultiMap.empty[K[_], V[_]])
  }
  type ServiceRegistry = TypedORMultiMap[ServiceKey, ActorRef]
  object ServiceRegistry {
    def empty: ServiceRegistry = TypedORMultiMap.empty
    def apply(map: ORMultiMap[ServiceKey[_], ActorRef[_]]): ServiceRegistry = TypedORMultiMap[ServiceKey, ActorRef](map)
  }

  private case object RemoveTick

  def behavior: Behavior[Command] = clusterBehavior
  val clusterBehavior: Behavior[Command] = ReceptionistImpl.init(ctx ⇒ clusteredReceptionist(ctx))

  object ClusterReceptionistSettings {
    def apply(system: ActorSystem[_]): ClusterReceptionistSettings =
      apply(system.settings.config.getConfig("akka.cluster.typed.receptionist"))

    def apply(config: Config): ClusterReceptionistSettings = {
      val writeTimeout = 5.seconds // the timeout is not important
      val writeConsistency = {
        val key = "write-consistency"
        toRootLowerCase(config.getString(key)) match {
          case "local"    ⇒ Replicator.WriteLocal
          case "majority" ⇒ Replicator.WriteMajority(writeTimeout)
          case "all"      ⇒ Replicator.WriteAll(writeTimeout)
          case _          ⇒ Replicator.WriteTo(config.getInt(key), writeTimeout)
        }
      }
      ClusterReceptionistSettings(
        writeConsistency,
        pruningInterval = config.getDuration("pruning-interval", MILLISECONDS).millis
      )
    }
  }

  case class ClusterReceptionistSettings(
    writeConsistency: WriteConsistency,
    pruningInterval:  FiniteDuration)

  /**
   * Returns an ReceptionistImpl.ExternalInterface that synchronizes registered services with
   */
  def clusteredReceptionist(ctx: ActorContext[AllCommands]): ReceptionistImpl.ExternalInterface[ServiceRegistry] = {
    import akka.actor.typed.scaladsl.adapter._
    val untypedSystem = ctx.system.toUntyped

    val settings = ClusterReceptionistSettings(ctx.system)

    val replicator = DistributedData(untypedSystem).replicator
    implicit val cluster = Cluster(untypedSystem)

    var state = ServiceRegistry.empty

    def diff(lastState: ServiceRegistry, newState: ServiceRegistry): Map[AbstractServiceKey, Set[ActorRef[_]]] = {
      def changesForKey[T](registry: Map[AbstractServiceKey, Set[ActorRef[_]]], key: ServiceKey[T]): Map[AbstractServiceKey, Set[ActorRef[_]]] = {
        val oldValues = lastState.getOrEmpty(key)
        val newValues = newState.getOrEmpty(key)
        if (oldValues != newValues)
          registry + (key → newValues.asInstanceOf[Set[ActorRef[_]]])
        else
          registry
      }

      val allKeys = lastState.toORMultiMap.entries.keySet ++ newState.toORMultiMap.entries.keySet
      allKeys
        .foldLeft(Map.empty[AbstractServiceKey, Set[ActorRef[_]]])(changesForKey(_, _))
    }

    val externalInterface = new ExternalInterface[ServiceRegistry] {
      private def updateRegistry(update: ServiceRegistry ⇒ ServiceRegistry): Unit = {
        state = update(state)
        replicator ! Replicator.Update(ReceptionistKey, EmptyORMultiMap, settings.writeConsistency) { registry ⇒
          update(ServiceRegistry(registry)).toORMultiMap
        }
      }

      def onRegister[T](key: ServiceKey[T], address: ActorRef[T]): Unit =
        updateRegistry(_.addBinding(key, address))

      def onUnregister[T](key: ServiceKey[T], address: ActorRef[T]): Unit =
        updateRegistry(_.removeBinding(key, address))

      def onExternalUpdate(update: ServiceRegistry): Unit = {
        state = update
      }
    }

    val replicatorMessageAdapter: ActorRef[Replicator.ReplicatorMessage] =
      ctx.messageAdapter[Replicator.ReplicatorMessage] {
        case changed @ Replicator.Changed(ReceptionistKey) ⇒
          val value = changed.get(ReceptionistKey)
          val oldState = state
          val newState = ServiceRegistry(value)
          val changes = diff(oldState, newState)
          externalInterface.RegistrationsChangedExternally(changes, newState)
      }

    replicator ! Replicator.Subscribe(ReceptionistKey, replicatorMessageAdapter.toUntyped)

    // remove entries when members are removed
    val clusterEventMessageAdapter: ActorRef[MemberRemoved] =
      ctx.messageAdapter[MemberRemoved] {
        case MemberRemoved(member, _) ⇒
          // ok to update from several nodes but more efficient to try to do it from one node
          if (cluster.state.leader.contains(cluster.selfAddress)) {
            if (member.address == cluster.selfAddress) NodesRemoved.empty
            else NodesRemoved(Set(member.address))
          } else
            NodesRemoved.empty
      }

    cluster.subscribe(clusterEventMessageAdapter.toUntyped, ClusterEvent.InitialStateAsEvents, classOf[MemberRemoved])

    // also periodic cleanup in case removal from ORMultiMap is skipped due to concurrent update,
    // which is possible for OR CRDTs
    val removeTickMessageAdapter: ActorRef[RemoveTick.type] =
      ctx.messageAdapter[RemoveTick.type] { _ ⇒
        // ok to update from several nodes but more efficient to try to do it from one node
        if (cluster.state.leader.contains(cluster.selfAddress)) {
          val allAddressesInState: Set[Address] = state.map.entries.flatMap {
            case (_, values) ⇒
              // don't care about local (empty host:port addresses)
              values.collect { case ref if ref.path.address.hasGlobalScope ⇒ ref.path.address }
          }(collection.breakOut)
          val clusterAddresses = cluster.state.members.map(_.address)
          val diff = allAddressesInState diff clusterAddresses
          if (diff.isEmpty) NodesRemoved.empty else NodesRemoved(diff)
        } else
          NodesRemoved.empty
      }

    ctx.system.scheduler.schedule(settings.pruningInterval, settings.pruningInterval,
      removeTickMessageAdapter.toUntyped, RemoveTick)(ctx.system.executionContext)

    externalInterface
  }
}
