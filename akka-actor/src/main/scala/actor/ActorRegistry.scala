/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.actor

import scala.collection.mutable.ListBuffer
import scala.reflect.Manifest

import java.util.concurrent.{ConcurrentSkipListSet, ConcurrentHashMap}
import java.util.{Set => JSet}

import se.scalablesolutions.akka.util.ListenerManagement
import annotation.tailrec

/**
 * Base trait for ActorRegistry events, allows listen to when an actor is added and removed from the ActorRegistry.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
sealed trait ActorRegistryEvent
case class ActorRegistered(actor: ActorRef) extends ActorRegistryEvent
case class ActorUnregistered(actor: ActorRef) extends ActorRegistryEvent

/**
 * Registry holding all Actor instances in the whole system.
 * Mapped by:
 * <ul>
 * <li>the Actor's UUID</li>
 * <li>the Actor's id field (which can be set by user-code)</li>
 * <li>the Actor's class</li>
 * <li>all Actors that are subtypes of a specific type</li>
 * <ul>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object ActorRegistry extends ListenerManagement {
  private val actorsByUUID =          new ConcurrentHashMap[String, ActorRef]
  private val actorsById =            new Index[String,ActorRef]
  
  /**
   * Returns all actors in the system.
   */
  def actors: Array[ActorRef] = filter(_ => true)

  /**
   * Invokes a function for all actors.
   */
  def foreach(f: (ActorRef) => Unit) = {
    val elements = actorsByUUID.elements
    while (elements.hasMoreElements) f(elements.nextElement)
  }

  /**
   * Invokes the function on all known actors until it returns Some
   * Returns None if the function never returns Some
   */
  def find[T](f: PartialFunction[ActorRef,T]) : Option[T] = {
    val elements = actorsByUUID.elements
    while (elements.hasMoreElements) {
      val element = elements.nextElement
      if(f isDefinedAt element)
        return Some(f(element))
    }
    None
  }

  /**
   * Finds all actors that are subtypes of the class passed in as the Manifest argument and supproting passed message.
   */
  def actorsFor[T <: Actor](message: Any)(implicit manifest: Manifest[T] ): Array[ActorRef] =
    filter(a => manifest.erasure.isAssignableFrom(a.actor.getClass) && a.isDefinedAt(message))

  /**
   * Finds all actors that satisfy a predicate.
   */
  def filter(p: ActorRef => Boolean): Array[ActorRef] = {
   val all = new ListBuffer[ActorRef]
    val elements = actorsByUUID.elements
    while (elements.hasMoreElements) {
      val actorId = elements.nextElement
      if (p(actorId))  {
        all += actorId
      }
    }
    all.toArray
  }

  /**
   * Finds all actors that are subtypes of the class passed in as the Manifest argument.
   */
  def actorsFor[T <: Actor](implicit manifest: Manifest[T]): Array[ActorRef] =
    actorsFor[T](manifest.erasure.asInstanceOf[Class[T]])

  /**
   * Finds any actor that matches T.
   */
  def actorFor[T <: Actor](implicit manifest: Manifest[T]): Option[ActorRef] =
    find({ case a:ActorRef if manifest.erasure.isAssignableFrom(a.actor.getClass) => a })

  /**
   * Finds all actors of type or sub-type specified by the class passed in as the Class argument.
   */
  def actorsFor[T <: Actor](clazz: Class[T]): Array[ActorRef] =
    filter(a => clazz.isAssignableFrom(a.actor.getClass))

  /**
   * Finds all actors that has a specific id.
   */
  def actorsFor(id: String): Array[ActorRef] = actorsById values id

   /**
   * Finds the actor that has a specific UUID.
   */
  def actorFor(uuid: String): Option[ActorRef] = Option(actorsByUUID get uuid)

  /**
   * Registers an actor in the ActorRegistry.
   */
  def register(actor: ActorRef) = {
    // ID
    actorsById.put(actor.id, actor)

    // UUID
    actorsByUUID.put(actor.uuid, actor)

    // notify listeners
    foreachListener(_ ! ActorRegistered(actor))
  }

  /**
   * Unregisters an actor in the ActorRegistry.
   */
  def unregister(actor: ActorRef) = {
    actorsByUUID remove actor.uuid

    actorsById.remove(actor.id,actor)

    // notify listeners
    foreachListener(_ ! ActorUnregistered(actor))
  }

  /**
   * Shuts down and unregisters all actors in the system.
   */
  def shutdownAll() {
    log.info("Shutting down all actors in the system...")
    foreach(_.stop)
    actorsByUUID.clear
    actorsById.clear
    log.info("All actors have been shut down and unregistered from ActorRegistry")
  }
}

class Index[K <: AnyRef,V <: AnyRef : Manifest] {
  import scala.collection.JavaConversions._
  
  private val Naught = Array[V]() //Nil for Arrays
  private val container = new ConcurrentHashMap[K, JSet[V]]
  private val emptySet = new ConcurrentSkipListSet[V]

  def put(key: K, value: V) {

    //Returns whether it needs to be retried or not
    def tryPut(set: JSet[V], v: V): Boolean = {
      set.synchronized {
          if (!set.isEmpty) {
            set add v
            false
          } else true
      }
    }

    @tailrec def syncPut(k: K, v: V): Boolean = {
      var retry = false
      val set = container get k
      if (set ne null) retry = tryPut(set,v)
      else {
        val newSet = new ConcurrentSkipListSet[V]
        newSet add v

        // Parry for two simultaneous putIfAbsent(id,newSet)
        val oldSet = container.putIfAbsent(k,newSet)
        if (oldSet ne null)
          retry = tryPut(oldSet,v)
      }

      if (retry) syncPut(k,v)
      else true
    }

    syncPut(key,value)
  }

  def values(key: K) = {
    val set: JSet[V] = container get key
    if (set ne null) set toArray Naught
    else Naught
  }

  def foreach(key: K)(fun: (V) => Unit) {
    val set = container get key
    if (set ne null)
     set foreach fun
  }

  def foreach(fun: (K,V) => Unit) {
    container.entrySet foreach {
      (e) => e.getValue.foreach(fun(e.getKey,_))
    }
  }

  def remove(key: K, value: V) {
    val set = container get key
    if (set ne null) {
      set.synchronized {
        if (set.remove(value)) {
          if (set.isEmpty)
            container.remove(key,emptySet)
        }
      }
    }
  }

  def clear = container.clear
}