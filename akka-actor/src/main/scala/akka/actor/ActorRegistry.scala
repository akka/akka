/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import scala.collection.mutable.ListBuffer

import java.util.concurrent.ConcurrentHashMap

import akka.util.ListenerManagement

/**
 * Base trait for ActorRegistry events, allows listen to when an actor is added and removed from the ActorRegistry.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
sealed trait ActorRegistryEvent
case class ActorRegistered(address: String, actor: ActorRef, typedActor: Option[AnyRef]) extends ActorRegistryEvent
case class ActorUnregistered(address: String, actor: ActorRef, typedActor: Option[AnyRef]) extends ActorRegistryEvent

/**
 * Registry holding all Actor instances in the whole system.
 * Mapped by address which is a unique string.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
private[actor] final class ActorRegistry private[actor] () extends ListenerManagement {
  private val actorsByAddress = new ConcurrentHashMap[String, ActorRef]
  private val actorsByUuid = new ConcurrentHashMap[Uuid, ActorRef]
  private val typedActorsByUuid = new ConcurrentHashMap[Uuid, AnyRef]

  val local = new LocalActorRegistry(actorsByAddress, actorsByUuid, typedActorsByUuid)

  /**
   * Finds the actor that has a specific address.
   */
  def actorFor(address: String): Option[ActorRef] = {
    if (actorsByAddress.containsKey(address)) Some(actorsByAddress.get(address))
    else None
  }

  /**
   * Finds the typed actors that have a specific address.
   */
  def typedActorFor(address: String): Option[AnyRef] =
    actorFor(address) map (typedActorFor(_))

  /**
   *  Registers an actor in the ActorRegistry.
   */
  private[akka] def register(actor: ActorRef) {
    val address = actor.address

    // FIXME: this check is nice but makes serialization/deserialization specs break
    //if (actorsByAddress.containsKey(address) || registeredInCluster(address))
    //  throw new IllegalStateException("Actor 'address' [" + address + "] is already in use, can't register actor [" + actor + "]")

    actorsByAddress.put(address, actor)
    actorsByUuid.put(actor.uuid, actor)
    notifyListeners(ActorRegistered(address, actor, Option(typedActorsByUuid get actor.uuid)))
  }

  private[akka] def registerTypedActor(actorRef: ActorRef, interface: AnyRef): Unit =
    typedActorsByUuid.put(actorRef.uuid, interface)

  private[akka] def unregisterTypedActor(actorRef: ActorRef, interface: AnyRef): Unit =
    typedActorsByUuid.remove(actorRef.uuid, interface)

  /**
   * Unregisters an actor in the ActorRegistry.
   */
  private[akka] def unregister(address: String) {
    val actor = actorsByAddress remove address
    actorsByUuid remove actor.uuid
    notifyListeners(ActorUnregistered(address, actor, None))
  }

  /**
   * Unregisters an actor in the ActorRegistry.
   */
  private[akka] def unregister(actor: ActorRef) {
    val address = actor.address
    actorsByAddress remove address
    actorsByUuid remove actor.uuid
    notifyListeners(ActorUnregistered(address, actor, Option(typedActorsByUuid remove actor.uuid)))
  }

  /**
   *  Registers an actor in the Cluster ActorRegistry.
   */
  // private[akka] def registerInCluster[T <: Actor](
  //   address: String, actorRef: ActorRef, replicas: Int, serializeMailbox: Boolean = false)(implicit format: Serializer) {
  //   // FIXME: implement ActorRegistry.registerInCluster(..)
  // }

  /**
   *  Unregisters an actor in the Cluster ActorRegistry.
   */
  // private[akka] def unregisterInCluster(address: String) {
  //   ClusterModule.node.remove(address)
  // }

  /**
   * Get the typed actor proxy for a given typed actor ref.
   */
  private def typedActorFor(actorRef: ActorRef): Option[AnyRef] =
    Option(typedActorsByUuid.get(actorRef.uuid))
}

/**
 * Projection over the local actor registry.
 */
class LocalActorRegistry(
  private val actorsByAddress: ConcurrentHashMap[String, ActorRef],
  private val actorsByUuid: ConcurrentHashMap[Uuid, ActorRef],
  private val typedActorsByUuid: ConcurrentHashMap[Uuid, AnyRef]) {

  // NOTE: currently ClusterActorRef's are only taken into account in 'actorFor(..)' - not in 'find', 'filter' etc.
  private val clusterActorRefsByAddress = new ConcurrentHashMap[String, ActorRef]
  private val clusterActorRefsByUuid = new ConcurrentHashMap[Uuid, ActorRef]

  /**
   * Returns the number of actors in the system.
   */
  def size: Int = actorsByAddress.size

  /**
   * Shuts down and unregisters all actors in the system.
   */
  def shutdownAll() {
    foreach(_.stop)
    actorsByAddress.clear()
    actorsByUuid.clear()
    typedActorsByUuid.clear()
  }

  //============== ACTORS ==============

  /**
   * Finds the actor that have a specific address.
   *
   * If a ClusterActorRef exists in the registry, then return that before we look after a LocalActorRef.
   */
  def actorFor(address: String): Option[ActorRef] = {
    if (clusterActorRefsByAddress.containsKey(address)) Some(clusterActorRefsByAddress.get(address))
    else if (actorsByAddress.containsKey(address)) Some(actorsByAddress.get(address))
    else None
  }

  private[akka] def actorFor(uuid: Uuid): Option[ActorRef] =
    if (clusterActorRefsByUuid.containsKey(uuid)) Some(clusterActorRefsByUuid.get(uuid))
    else if (actorsByUuid.containsKey(uuid)) Some(actorsByUuid.get(uuid))
    else None

  // By-passes checking the registry for ClusterActorRef and only returns possible LocalActorRefs
  private[akka] def localActorRefFor(address: String): Option[ActorRef] = {
    if (actorsByAddress.containsKey(address)) Some(actorsByAddress.get(address))
    else None
  }

  // By-passes checking the registry for ClusterActorRef and only returns possible LocalActorRefs
  private[akka] def localActorRefFor(uuid: Uuid): Option[ActorRef] =
    if (actorsByUuid.containsKey(uuid)) Some(actorsByUuid.get(uuid))
    else None

  /**
   * Finds the typed actor that have a specific address.
   */
  def typedActorFor(address: String): Option[AnyRef] =
    actorFor(address) map (typedActorFor(_)) getOrElse None

  /**
   * Finds the typed actor that have a specific uuid.
   */
  private[akka] def typedActorFor(uuid: Uuid): Option[AnyRef] =
    Option(typedActorsByUuid.get(uuid))

  /**
   * Returns all actors in the system.
   */
  def actors: Array[ActorRef] = filter(_ ⇒ true)

  /**
   * Invokes a function for all actors.
   */
  def foreach(f: (ActorRef) ⇒ Unit) = {
    val elements = actorsByAddress.elements
    while (elements.hasMoreElements) f(elements.nextElement)
  }

  /**
   * Invokes the function on all known actors until it returns Some
   * Returns None if the function never returns Some
   */
  def find[T](f: PartialFunction[ActorRef, T]): Option[T] = {
    val elements = actorsByAddress.elements
    while (elements.hasMoreElements) {
      val element = elements.nextElement
      if (f isDefinedAt element) return Some(f(element))
    }
    None
  }

  /**
   * Finds all actors that satisfy a predicate.
   */
  def filter(p: ActorRef ⇒ Boolean): Array[ActorRef] = {
    val all = new ListBuffer[ActorRef]
    val elements = actorsByAddress.elements
    while (elements.hasMoreElements) {
      val actorId = elements.nextElement
      if (p(actorId)) all += actorId
    }
    all.toArray
  }

  //============== TYPED ACTORS ==============

  /**
   * Returns all typed actors in the system.
   */
  def typedActors: Array[AnyRef] = filterTypedActors(_ ⇒ true)

  /**
   * Invokes a function for all typed actors.
   */
  def foreachTypedActor(f: (AnyRef) ⇒ Unit) = {
    val i = typedActorsByUuid.values.iterator
    while (i.hasNext)
      f(i.next)
  }

  /**
   * Invokes the function on all known typed actors until it returns Some
   * Returns None if the function never returns Some
   */
  def findTypedActor[T](f: PartialFunction[AnyRef, T]): Option[T] = {
    val i = typedActorsByUuid.values.iterator
    while (i.hasNext) {
      val proxy = i.next
      if (f isDefinedAt proxy) return Some(f(proxy))
    }

    None
  }

  /**
   * Finds all typed actors that satisfy a predicate.
   */
  def filterTypedActors(p: AnyRef ⇒ Boolean): Array[AnyRef] = {
    val all = new ListBuffer[AnyRef]
    val i = typedActorsByUuid.values.iterator
    while (i.hasNext) {
      val proxy = i.next
      if (p(proxy)) all += proxy
    }

    all.toArray
  }

  /**
   * Get the typed actor proxy for a given typed actor ref.
   */
  private def typedActorFor(actorRef: ActorRef): Option[AnyRef] =
    typedActorFor(actorRef.uuid)

  /**
   *  Registers an ClusterActorRef in the ActorRegistry.
   */
  private[akka] def registerClusterActorRef(actor: ActorRef) {
    val address = actor.address
    clusterActorRefsByAddress.put(address, actor)
    clusterActorRefsByUuid.put(actor.uuid, actor)
  }

  /**
   * Unregisters an ClusterActorRef in the ActorRegistry.
   */
  private[akka] def unregisterClusterActorRef(address: String) {
    val actor = clusterActorRefsByAddress remove address
    clusterActorRefsByUuid remove actor.uuid
  }

  /**
   * Unregisters an ClusterActorRef in the ActorRegistry.
   */
  private[akka] def unregisterClusterActorRef(actor: ActorRef) {
    unregisterClusterActorRef(actor.address)
  }
}
