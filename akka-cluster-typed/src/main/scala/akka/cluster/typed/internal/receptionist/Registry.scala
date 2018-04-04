/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.typed.internal.receptionist

import akka.actor.typed.ActorRef
import akka.actor.typed.internal.receptionist.AbstractServiceKey
import akka.actor.typed.receptionist.ServiceKey
import akka.annotation.InternalApi
import akka.cluster.{ Cluster, UniqueAddress }
import akka.cluster.ddata.{ ORMultiMap, ORMultiMapKey }
import akka.cluster.typed.internal.receptionist.ClusterReceptionist.{ DDataKey, EmptyORMultiMap, Entry }

/**
 * INTERNAL API
 */
@InternalApi private[akka] object SuperServiceRegistry {
  def apply(numberOfKeys: Int): SuperServiceRegistry = {
    val map = (0 until numberOfKeys).map { n ⇒
      val key = ORMultiMapKey[ServiceKey[_], Entry](s"ReceptionistKey_$n")
      key -> new ServiceRegistry(EmptyORMultiMap)
    }.toMap
    new SuperServiceRegistry(map)
  }

}

/**
 * Two level structure for keeping service registry to be able to shard entries over multiple ddata keys (to not
 * get too large ddata messages)
 *
 * INTERNAL API
 */
@InternalApi private[akka] final class SuperServiceRegistry(serviceRegistries: Map[DDataKey, ServiceRegistry]) {

  private val keys = serviceRegistries.keySet.toArray

  def registryFor(ddataKey: DDataKey): ServiceRegistry = serviceRegistries(ddataKey)

  def allDdataKeys: Iterable[DDataKey] = keys

  def ddataKeyFor(serviceKey: ServiceKey[_]): DDataKey =
    keys(math.abs(serviceKey.hashCode() % serviceRegistries.size))

  def allServices: Iterator[(ServiceKey[_], Set[Entry])] =
    serviceRegistries.valuesIterator.flatMap(_.map.entries)

  def allEntries: Iterator[Entry] = allServices.flatMap(_._2)

  def getActorRefsFor[T](key: ServiceKey[T]): Set[ActorRef[T]] = {
    val dDataKey = ddataKeyFor(key)
    serviceRegistries(dDataKey).getActorRefsFor(key)
  }

  def withServiceRegistry(dDataKey: DDataKey, registry: ServiceRegistry): SuperServiceRegistry =
    new SuperServiceRegistry(serviceRegistries + (dDataKey -> registry))

  def allUniqueAddressesInState(selfUniqueAddress: UniqueAddress): Set[UniqueAddress] =
    allEntries.collect {
      case entry if entry.ref.path.address.hasGlobalScope ⇒
        entry.uniqueAddress(selfUniqueAddress)
    }.toSet

  def collectChangedKeys(dDataKey: DDataKey, newRegistry: ServiceRegistry): Set[AbstractServiceKey] = {
    val previousRegistry = registryFor(dDataKey)
    ServiceRegistry.collectChangedKeys(previousRegistry, newRegistry)
  }

}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final case class ServiceRegistry(map: ORMultiMap[ServiceKey[_], Entry]) extends AnyVal {

  // let's hide all the ugly casts we can in here
  def getActorRefsFor[T](key: AbstractServiceKey): Set[ActorRef[key.Protocol]] =
    getEntriesFor(key).map(_.ref.asInstanceOf[ActorRef[key.Protocol]])

  def getEntriesFor(key: AbstractServiceKey): Set[Entry] =
    map.getOrElse(key.asServiceKey, Set.empty[Entry])

  def addBinding[T](key: ServiceKey[T], value: Entry)(implicit cluster: Cluster): ServiceRegistry =
    ServiceRegistry(map.addBinding(key, value))

  def removeBinding[T](key: ServiceKey[T], value: Entry)(implicit cluster: Cluster): ServiceRegistry =
    ServiceRegistry(map.removeBinding(key, value))

  def removeAll(removals: Map[AbstractServiceKey, Set[Entry]])(implicit cluster: Cluster): ServiceRegistry = {
    removals.foldLeft(this) {
      case (acc, (key, entries)) ⇒
        entries.foldLeft(acc) {
          case (innerAcc, entry) ⇒
            innerAcc.removeBinding[key.Protocol](key.asServiceKey, entry)
        }
    }
  }

  def toORMultiMap: ORMultiMap[ServiceKey[_], Entry] = map

}
object ServiceRegistry {
  final val Empty = ServiceRegistry(EmptyORMultiMap)

  def collectChangedKeys(previousRegistry: ServiceRegistry, newRegistry: ServiceRegistry): Set[AbstractServiceKey] = {
    val allKeys = previousRegistry.toORMultiMap.entries.keySet ++ newRegistry.toORMultiMap.entries.keySet
    allKeys.foldLeft(Set.empty[AbstractServiceKey]) { (acc, key) ⇒
      val oldValues = previousRegistry.getEntriesFor(key)
      val newValues = newRegistry.getEntriesFor(key)
      if (oldValues != newValues) acc + key
      else acc
    }
  }
}
