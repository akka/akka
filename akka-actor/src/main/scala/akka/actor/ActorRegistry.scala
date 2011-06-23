/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import scala.collection.mutable.ListBuffer
import scala.reflect.Manifest
import annotation.tailrec

import java.util.concurrent.{ ConcurrentSkipListSet, ConcurrentHashMap }
import java.util.{ Set ⇒ JSet }

import akka.util.ReflectiveAccess._
import akka.util.ListenerManagement
import akka.serialization._

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

  //private val isClusterEnabled = ReflectiveAccess.isClusterEnabled
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

  private[akka] def registerTypedActor(actorRef: ActorRef, interface: AnyRef) {
    typedActorsByUuid.put(actorRef.uuid, interface)
    actorRef.start // register actorRef
  }

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
  private[akka] def registerInCluster[T <: Actor](
    address: String, actorRef: ActorRef, replicas: Int, serializeMailbox: Boolean = false)(implicit format: Serializer) {
    ClusterModule.node.store(actorRef, replicas, serializeMailbox, format)
  }

  /**
   *  Unregisters an actor in the Cluster ActorRegistry.
   */
  private[akka] def unregisterInCluster(address: String) {
    ClusterModule.node.remove(address)
  }

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
   */
  def actorFor(address: String): Option[ActorRef] = {
    if (actorsByAddress.containsKey(address)) Some(actorsByAddress.get(address))
    else None
  }

  /**
   * Finds the actor that have a specific uuid.
   */
  private[akka] def actorFor(uuid: Uuid): Option[ActorRef] =
    Option(actorsByUuid.get(uuid))

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
}

/**
 * FIXME move Index to its own file and put in akka.util.
 *
 * An implementation of a ConcurrentMultiMap
 * Adds/remove is serialized over the specified key
 * Reads are fully concurrent <-- el-cheapo
 *
 * @author Viktor Klang
 */
class Index[K <: AnyRef, V <: AnyRef: Manifest] {
  private val Naught = Array[V]() //Nil for Arrays
  private val container = new ConcurrentHashMap[K, JSet[V]]
  private val emptySet = new ConcurrentSkipListSet[V]

  /**
   * Associates the value of type V with the key of type K
   * @return true if the value didn't exist for the key previously, and false otherwise
   */
  def put(key: K, value: V): Boolean = {
    //Tailrecursive spin-locking put
    @tailrec
    def spinPut(k: K, v: V): Boolean = {
      var retry = false
      var added = false
      val set = container get k

      if (set ne null) {
        set.synchronized {
          if (set.isEmpty) retry = true //IF the set is empty then it has been removed, so signal retry
          else { //Else add the value to the set and signal that retry is not needed
            added = set add v
            retry = false
          }
        }
      } else {
        val newSet = new ConcurrentSkipListSet[V]
        newSet add v

        // Parry for two simultaneous putIfAbsent(id,newSet)
        val oldSet = container.putIfAbsent(k, newSet)
        if (oldSet ne null) {
          oldSet.synchronized {
            if (oldSet.isEmpty) retry = true //IF the set is empty then it has been removed, so signal retry
            else { //Else try to add the value to the set and signal that retry is not needed
              added = oldSet add v
              retry = false
            }
          }
        } else added = true
      }

      if (retry) spinPut(k, v)
      else added
    }

    spinPut(key, value)
  }

  /**
   * @return a _new_ array of all existing values for the given key at the time of the call
   */
  def values(key: K): Array[V] = {
    val set: JSet[V] = container get key
    val result = if (set ne null) set toArray Naught else Naught
    result.asInstanceOf[Array[V]]
  }

  /**
   * @return Some(value) for the first matching value where the supplied function returns true for the given key,
   * if no matches it returns None
   */
  def findValue(key: K)(f: (V) ⇒ Boolean): Option[V] = {
    import scala.collection.JavaConversions._
    val set = container get key
    if (set ne null) set.iterator.find(f)
    else None
  }

  /**
   * Applies the supplied function to all keys and their values
   */
  def foreach(fun: (K, V) ⇒ Unit) {
    import scala.collection.JavaConversions._
    container.entrySet foreach { e ⇒ e.getValue.foreach(fun(e.getKey, _)) }
  }

  /**
   * Disassociates the value of type V from the key of type K
   * @return true if the value was disassociated from the key and false if it wasn't previously associated with the key
   */
  def remove(key: K, value: V): Boolean = {
    val set = container get key

    if (set ne null) {
      set.synchronized {
        if (set.remove(value)) { //If we can remove the value
          if (set.isEmpty) //and the set becomes empty
            container.remove(key, emptySet) //We try to remove the key if it's mapped to an empty set

          true //Remove succeeded
        } else false //Remove failed
      }
    } else false //Remove failed
  }

  /**
   * @return true if the underlying containers is empty, may report false negatives when the last remove is underway
   */
  def isEmpty: Boolean = container.isEmpty

  /**
   *  Removes all keys and all values
   */
  def clear = foreach { case (k, v) ⇒ remove(k, v) }
}
