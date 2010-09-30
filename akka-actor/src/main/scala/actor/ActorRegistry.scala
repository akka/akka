/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.actor

import scala.collection.mutable.ListBuffer
import scala.reflect.Manifest

import java.util.concurrent.{ConcurrentSkipListSet, ConcurrentHashMap}
import java.util.{Set => JSet}

import annotation.tailrec
import se.scalablesolutions.akka.util.ListenerManagement
import se.scalablesolutions.akka.util.ReflectiveAccess._

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
  private val actorsByUUID =          new ConcurrentHashMap[Uuid, ActorRef]
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
  def actorFor(uuid: Uuid): Option[ActorRef] = Option(actorsByUUID get uuid)

  /**
   * Returns all typed actors in the system.
   */
  def typedActors: Array[AnyRef] = filterTypedActors(_ => true)

  /**
   * Invokes a function for all typed actors.
   */
  def foreachTypedActor(f: (AnyRef) => Unit) = {
    val elements = actorsByUUID.elements
    while (elements.hasMoreElements) {
      val proxy = typedActorFor(elements.nextElement)
      if (proxy.isDefined) {
        f(proxy.get)
      }
    }
  }

  /**
   * Invokes the function on all known typed actors until it returns Some
   * Returns None if the function never returns Some
   */
  def findTypedActor[T](f: PartialFunction[AnyRef,T]) : Option[T] = {
    val elements = actorsByUUID.elements
    while (elements.hasMoreElements) {
      val proxy = typedActorFor(elements.nextElement)
      if(proxy.isDefined && (f isDefinedAt proxy))
        return Some(f(proxy))
    }
    None
  }

  /**
   * Finds all typed actors that satisfy a predicate.
   */
  def filterTypedActors(p: AnyRef => Boolean): Array[AnyRef] = {
    TypedActorModule.ensureTypedActorEnabled
    val all = new ListBuffer[AnyRef]
    val elements = actorsByUUID.elements
    while (elements.hasMoreElements) {
      val proxy = typedActorFor(elements.nextElement)
      if (proxy.isDefined && p(proxy.get))  {
        all += proxy.get
      }
    }
    all.toArray
  }

  /**
   * Finds all typed actors that are subtypes of the class passed in as the Manifest argument.
   */
  def typedActorsFor[T <: AnyRef](implicit manifest: Manifest[T]): Array[AnyRef] = {
    TypedActorModule.ensureTypedActorEnabled
    typedActorsFor[T](manifest.erasure.asInstanceOf[Class[T]])
  }

  /**
   * Finds any typed actor that matches T.
   */
  def typedActorFor[T <: AnyRef](implicit manifest: Manifest[T]): Option[AnyRef] = {
    def predicate(proxy: AnyRef) : Boolean = {
      val actorRef = actorFor(proxy)
      actorRef.isDefined && manifest.erasure.isAssignableFrom(actorRef.get.actor.getClass)
    }
    findTypedActor({ case a:AnyRef if predicate(a) => a })
  }

  /**
   * Finds all typed actors of type or sub-type specified by the class passed in as the Class argument.
   */
  def typedActorsFor[T <: AnyRef](clazz: Class[T]): Array[AnyRef] = {
    TypedActorModule.ensureTypedActorEnabled
    def predicate(proxy: AnyRef) : Boolean = {
      val actorRef = actorFor(proxy)
      actorRef.isDefined && clazz.isAssignableFrom(actorRef.get.actor.getClass)
    }
    filterTypedActors(predicate)
  }

  /**
   * Finds all typed actors that have a specific id.
   */
  def typedActorsFor(id: String): Array[AnyRef] = {
    TypedActorModule.ensureTypedActorEnabled
    val actorRefs = actorsById values id
    actorRefs.flatMap(typedActorFor(_))
  }

  /**
   * Finds the typed actor that has a specific UUID.
   */
  def typedActorFor(uuid: Uuid): Option[AnyRef] = {
    TypedActorModule.ensureTypedActorEnabled
    val actorRef = actorsByUUID get uuid
    if (actorRef eq null)
      None
    else
      typedActorFor(actorRef)
  }

  /**
   * Get the typed actor proxy for a given typed actor ref.
   */
  private def typedActorFor(actorRef: ActorRef): Option[AnyRef] = {
    TypedActorModule.typedActorObjectInstance.get.proxyFor(actorRef)
  }

  /**
   * Get the underlying typed actor for a given proxy.
   */
  private def actorFor(proxy: AnyRef): Option[ActorRef] = {
    TypedActorModule.typedActorObjectInstance.get.actorFor(proxy)
  }


  /**
   * Registers an actor in the ActorRegistry.
   */
  def register(actor: ActorRef) = {
    // ID
    actorsById.put(actor.id, actor)

    // UUID
    actorsByUUID.put(actor.uuid, actor)

    // notify listeners
    notifyListeners(ActorRegistered(actor))
  }

  /**
   * Unregisters an actor in the ActorRegistry.
   */
  def unregister(actor: ActorRef) = {
    actorsByUUID remove actor.uuid

    actorsById.remove(actor.id,actor)

    // notify listeners
    notifyListeners(ActorUnregistered(actor))
  }

  /**
   * Shuts down and unregisters all actors in the system.
   */
  def shutdownAll() {
    log.info("Shutting down all actors in the system...")
    if (TypedActorModule.isTypedActorEnabled) {
      val elements = actorsByUUID.elements
      while (elements.hasMoreElements) {
        val actorRef = elements.nextElement
        val proxy = typedActorFor(actorRef)
        if (proxy.isDefined) {
          TypedActorModule.typedActorObjectInstance.get.stop(proxy.get)
        } else {
          actorRef.stop
        }
      }
    } else {
      foreach(_.stop)
    }
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
          if (set.isEmpty) true //IF the set is empty then it has been removed, so signal retry
          else { //Else add the value to the set and signal that retry is not needed
            set add v
            false
          }
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

  def findValue(key: K)(f: (V) => Boolean): Option[V] = {
    val set = container get key
    if (set ne null)
     set.iterator.find(f)
    else
     None
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
        if (set.remove(value)) { //If we can remove the value
          if (set.isEmpty)       //and the set becomes empty
            container.remove(key,emptySet) //We try to remove the key if it's mapped to an empty set
        }
      }
    }
  }

  def clear = { foreach(remove _) }
}