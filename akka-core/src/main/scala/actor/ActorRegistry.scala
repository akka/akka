/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.actor

import scala.collection.mutable.ListBuffer
import scala.reflect.Manifest
import java.util.concurrent.{CopyOnWriteArrayList, ConcurrentHashMap}
import se.scalablesolutions.akka.util.ListenerManagement

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
  private val actorsById =            new ConcurrentHashMap[String, List[ActorRef]]
  private val actorsByClassName =     new ConcurrentHashMap[String, List[ActorRef]]

  /**
   * Returns all actors in the system.
   */
  def actors: List[ActorRef] = {
    val all = new ListBuffer[ActorRef]
    val elements = actorsByUUID.elements
    while (elements.hasMoreElements) all += elements.nextElement
    all.toList
  }

  /**
   * Invokes a function for all actors.
   */
  def foreach(f: (ActorRef) => Unit) = {
    val elements = actorsByUUID.elements
    while (elements.hasMoreElements) f(elements.nextElement)
  }

  /**
   * Finds all actors that are subtypes of the class passed in as the Manifest argument.
   */
  def actorsFor[T <: Actor](implicit manifest: Manifest[T]): List[ActorRef] = {
    val all = new ListBuffer[ActorRef]
    val elements = actorsByUUID.elements
    while (elements.hasMoreElements) {
      val actorId = elements.nextElement
      if (manifest.erasure.isAssignableFrom(actorId.actor.getClass)) {
        all += actorId
      }
    }
    all.toList
  }

  /**
   * Finds all actors of the exact type specified by the class passed in as the Class argument.
   */
  def actorsFor[T <: Actor](clazz: Class[T]): List[ActorRef] = {
    if (actorsByClassName.containsKey(clazz.getName)) actorsByClassName.get(clazz.getName)
    else Nil
  }

  /**
   * Finds all actors that has a specific id.
   */
  def actorsFor(id: String): List[ActorRef] = {
    if (actorsById.containsKey(id)) actorsById.get(id)
    else Nil
  }

   /**
   * Finds the actor that has a specific UUID.
   */
  def actorFor(uuid: String): Option[ActorRef] = {
    if (actorsByUUID.containsKey(uuid)) Some(actorsByUUID.get(uuid))
    else None
  }

  /**
   * Registers an actor in the ActorRegistry.
   */
  def register(actor: ActorRef) = {
    // UUID
    actorsByUUID.put(actor.uuid, actor)

    // ID
    val id = actor.id
    if (id eq null) throw new IllegalStateException("Actor.id is null " + actor)
    if (actorsById.containsKey(id)) actorsById.put(id, actor :: actorsById.get(id))
    else actorsById.put(id, actor :: Nil)

    // Class name
    val className = actor.actor.getClass.getName
    if (actorsByClassName.containsKey(className)) {
      actorsByClassName.put(className, actor :: actorsByClassName.get(className))
    } else actorsByClassName.put(className, actor :: Nil)

    // notify listeners
    foreachListener(_ ! ActorRegistered(actor))
  }

  /**
   * FIXME: WRONG - unregisters all actors with the same id and class name, should remove the right one in each list
   * Unregisters an actor in the ActorRegistry.
   */
  def unregister(actor: ActorRef) = {
    actorsByUUID remove actor.uuid
    actorsById remove actor.id
    actorsByClassName remove actor.getClass.getName
    // notify listeners
    foreachListener(_ ! ActorUnregistered(actor))
  }

  /**
   * Shuts down and unregisters all actors in the system.
   */
  def shutdownAll = {
    log.info("Shutting down all actors in the system...")
    foreach(_.stop)
    actorsByUUID.clear
    actorsById.clear
    actorsByClassName.clear
    log.info("All actors have been shut down and unregistered from ActorRegistry")
  }
}
