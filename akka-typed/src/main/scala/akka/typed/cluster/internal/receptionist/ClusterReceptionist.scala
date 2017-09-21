/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.cluster.internal.receptionist

import akka.annotation.InternalApi
import akka.cluster.Cluster
import akka.cluster.ddata.DistributedData
import akka.cluster.ddata.ORMultiMap
import akka.cluster.ddata.ORMultiMapKey
import akka.cluster.ddata.Replicator
import akka.cluster.ddata.Replicator.WriteConsistency
import akka.typed.ActorRef
import akka.typed.Behavior
import akka.typed.internal.receptionist.ReceptionistBehaviorProvider
import akka.typed.internal.receptionist.ReceptionistImpl
import akka.typed.internal.receptionist.ReceptionistImpl._
import akka.typed.receptionist.Receptionist.AbstractServiceKey
import akka.typed.receptionist.Receptionist.AllCommands
import akka.typed.receptionist.Receptionist.Command
import akka.typed.receptionist.Receptionist.ServiceKey
import akka.typed.scaladsl.ActorContext

import scala.language.existentials
import scala.language.higherKinds

/** Internal API */
@InternalApi
private[typed] object ClusterReceptionist extends ReceptionistBehaviorProvider {
  private final val ReceptionistKey = ORMultiMapKey[ServiceKey[_], ActorRef[_]]("ReceptionistKey")
  private final val EmptyORMultiMap = ORMultiMap.empty[ServiceKey[_], ActorRef[_]]

  case class TypedORMultiMap[K[_], V[_]](val map: ORMultiMap[K[_], V[_]]) extends AnyVal {
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

  def behavior: Behavior[Command] = clusterBehavior
  val clusterBehavior: Behavior[Command] = ReceptionistImpl.init(clusteredReceptionist())

  case class ClusterReceptionistSettings(
    writeConsistency: WriteConsistency = Replicator.WriteLocal
  )

  /**
   * Returns an ReceptionistImpl.ExternalInterface that synchronizes registered services with
   */
  def clusteredReceptionist(settings: ClusterReceptionistSettings = ClusterReceptionistSettings())(ctx: ActorContext[AllCommands]): ReceptionistImpl.ExternalInterface = {
    import akka.typed.scaladsl.adapter._
    val untypedSystem = ctx.system.toUntyped

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

    val adapter: ActorRef[Replicator.ReplicatorMessage] =
      ctx.spawnAdapter[Replicator.ReplicatorMessage] { (x: Replicator.ReplicatorMessage) ⇒
        x match {
          case changed @ Replicator.Changed(ReceptionistKey) ⇒
            val value = changed.get(ReceptionistKey)
            val oldState = state
            state = ServiceRegistry(value) // is that thread-safe?
            val changes = diff(oldState, state)
            RegistrationsChangedExternally(changes)
        }
      }

    replicator ! Replicator.Subscribe(ReceptionistKey, adapter.toUntyped)

    new ExternalInterface {
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
    }
  }
}
