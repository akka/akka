/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.typed.internal.receptionist

import akka.actor.typed.ActorRef
import akka.actor.typed.internal.receptionist.AbstractServiceKey
import akka.actor.typed.receptionist.ServiceKey
import akka.annotation.InternalApi
import akka.cluster.UniqueAddress
import akka.cluster.ddata.{ ORMultiMap, ORMultiMapKey, SelfUniqueAddress }
import akka.cluster.typed.internal.receptionist.ClusterReceptionist.{ DDataKey, EmptyORMultiMap, Entry }

import scala.concurrent.duration.Deadline

/**
 * INTERNAL API
 */
@InternalApi private[akka] object ShardedServiceRegistry {
  def apply(numberOfKeys: Int): ShardedServiceRegistry = {
    val emptyRegistries = (0 until numberOfKeys).map { n =>
      val key = ORMultiMapKey[ServiceKey[_], Entry](s"ReceptionistKey_$n")
      key -> new ServiceRegistry(EmptyORMultiMap)
    }.toMap
    new ShardedServiceRegistry(emptyRegistries, Map.empty, Set.empty)
  }

}

/**
 * INTERNAL API
 *
 * Two level structure for keeping service registry to be able to shard entries over multiple ddata keys (to not
 * get too large ddata messages)
 *
 * @param tombstones Local actors that were stopped and should not be re-added to the available set of actors
 *                   for a key. Since the only way to unregister is to stop, we don't need to keep track of
 *                   the service key
 *
 */
@InternalApi private[akka] final case class ShardedServiceRegistry(
    serviceRegistries: Map[DDataKey, ServiceRegistry],
    tombstones: Map[ActorRef[_], Deadline],
    nodes: Set[UniqueAddress]) {

  private val keys = serviceRegistries.keySet.toArray

  def registryFor(ddataKey: DDataKey): ServiceRegistry = serviceRegistries(ddataKey)

  def allDdataKeys: Iterable[DDataKey] = keys

  def ddataKeyFor(serviceKey: ServiceKey[_]): DDataKey =
    keys(math.abs(serviceKey.id.hashCode() % serviceRegistries.size))

  def allServices: Iterator[(ServiceKey[_], Set[Entry])] =
    serviceRegistries.valuesIterator.flatMap(_.entries.entries)

  def allEntries: Iterator[Entry] = allServices.flatMap(_._2)

  def actorRefsFor[T](key: ServiceKey[T]): Set[ActorRef[T]] = {
    val ddataKey = ddataKeyFor(key)
    serviceRegistries(ddataKey).actorRefsFor(key)
  }

  def activeActorRefsFor[T](key: ServiceKey[T], selfUniqueAddress: UniqueAddress): Set[ActorRef[T]] = {
    val ddataKey = ddataKeyFor(key)
    val entries = serviceRegistries(ddataKey).entriesFor(key)
    val selfAddress = selfUniqueAddress.address
    entries.collect {
      case entry if nodes.contains(entry.uniqueAddress(selfAddress)) && !hasTombstone(entry.ref) =>
        entry.ref.asInstanceOf[ActorRef[key.Protocol]]
    }
  }

  def withServiceRegistry(ddataKey: DDataKey, registry: ServiceRegistry): ShardedServiceRegistry =
    copy(serviceRegistries + (ddataKey -> registry), tombstones)

  def allUniqueAddressesInState(selfUniqueAddress: UniqueAddress): Set[UniqueAddress] =
    allEntries.collect {
      // we don't care about local (empty host:port addresses)
      case entry if entry.ref.path.address.hasGlobalScope =>
        entry.uniqueAddress(selfUniqueAddress.address)
    }.toSet

  def collectChangedKeys(ddataKey: DDataKey, newRegistry: ServiceRegistry): Set[AbstractServiceKey] = {
    val previousRegistry = registryFor(ddataKey)
    ServiceRegistry.collectChangedKeys(previousRegistry, newRegistry)
  }

  def entriesPerDdataKey(
      entries: Map[AbstractServiceKey, Set[Entry]]): Map[DDataKey, Map[AbstractServiceKey, Set[Entry]]] =
    entries.foldLeft(Map.empty[DDataKey, Map[AbstractServiceKey, Set[Entry]]]) {
      case (acc, (key, entries)) =>
        val ddataKey = ddataKeyFor(key.asServiceKey)
        val updated = acc.getOrElse(ddataKey, Map.empty) + (key -> entries)
        acc + (ddataKey -> updated)
    }

  def addTombstone(actorRef: ActorRef[_], deadline: Deadline): ShardedServiceRegistry =
    copy(tombstones = tombstones + (actorRef -> deadline))

  def hasTombstone(actorRef: ActorRef[_]): Boolean =
    tombstones.nonEmpty && tombstones.contains(actorRef)

  def pruneTombstones(): ShardedServiceRegistry = {
    copy(tombstones = tombstones.filter {
      case (_, deadline) => deadline.hasTimeLeft
    })
  }

  def addNode(node: UniqueAddress): ShardedServiceRegistry =
    copy(nodes = nodes + node)

  def removeNode(node: UniqueAddress): ShardedServiceRegistry =
    copy(nodes = nodes - node)

}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final case class ServiceRegistry(entries: ORMultiMap[ServiceKey[_], Entry]) extends AnyVal {

  // let's hide all the ugly casts we can in here
  def actorRefsFor[T](key: AbstractServiceKey): Set[ActorRef[key.Protocol]] =
    entriesFor(key).map(_.ref.asInstanceOf[ActorRef[key.Protocol]])

  def entriesFor(key: AbstractServiceKey): Set[Entry] =
    entries.getOrElse(key.asServiceKey, Set.empty[Entry])

  def addBinding[T](key: ServiceKey[T], value: Entry)(implicit node: SelfUniqueAddress): ServiceRegistry =
    copy(entries = entries.addBinding(node, key, value))

  def removeBinding[T](key: ServiceKey[T], value: Entry)(implicit node: SelfUniqueAddress): ServiceRegistry =
    copy(entries = entries.removeBinding(node, key, value))

  def removeAll(entries: Map[AbstractServiceKey, Set[Entry]])(implicit node: SelfUniqueAddress): ServiceRegistry = {
    entries.foldLeft(this) {
      case (acc, (key, entries)) =>
        entries.foldLeft(acc) {
          case (innerAcc, entry) =>
            innerAcc.removeBinding[key.Protocol](key.asServiceKey, entry)
        }
    }
  }

  def toORMultiMap: ORMultiMap[ServiceKey[_], Entry] = entries

}

/**
 * INTERNAL API
 */
@InternalApi private[akka] object ServiceRegistry {
  final val Empty = ServiceRegistry(EmptyORMultiMap)

  def collectChangedKeys(previousRegistry: ServiceRegistry, newRegistry: ServiceRegistry): Set[AbstractServiceKey] = {
    val allKeys = previousRegistry.toORMultiMap.entries.keySet ++ newRegistry.toORMultiMap.entries.keySet
    allKeys.foldLeft(Set.empty[AbstractServiceKey]) { (acc, key) =>
      val oldValues = previousRegistry.entriesFor(key)
      val newValues = newRegistry.entriesFor(key)
      if (oldValues != newValues) acc + key
      else acc
    }
  }
}
