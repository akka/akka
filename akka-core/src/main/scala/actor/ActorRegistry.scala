/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.actor

import se.scalablesolutions.akka.util.Logging

import scala.collection.mutable.{ListBuffer, HashMap}
import scala.reflect.Manifest

/**
 * Registry holding all actor instances, mapped by class and the actor's id field (which can be set by user-code).
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object ActorRegistry extends Logging {
  private val actorsByClassName = new HashMap[String, List[Actor]]
  private val actorsById = new HashMap[String, List[Actor]]

  /**
   * Returns all actors in the system.
   */
  def actors: List[Actor] = synchronized {
    val all = new ListBuffer[Actor]
    actorsById.values.foreach(all ++= _)
    all.toList
  }

  /**
   * Invokes a function for all actors.
   */
  def foreach(f: (Actor) => Unit) = actors.foreach(f)

  /**
   * Finds all actors that are subtypes of the class passed in as the Manifest argument.
   */
  def actorsFor[T <: Actor](implicit manifest: Manifest[T]): List[T] = synchronized {
    for (actor <- actors; if manifest.erasure.isAssignableFrom(actor.getClass)) yield actor.asInstanceOf[T]
  }

  /**
   * Finds all actors of the exact type specified by the class passed in as the Class argument.
   */
  def actorsFor[T <: Actor](clazz: Class[T]): List[T] = synchronized {
    actorsByClassName.get(clazz.getName) match {
      case None => Nil
      case Some(instances) => instances.asInstanceOf[List[T]]
    }
  }

  /**
   * Finds all actors that has a specific id.
   */
  def actorsFor(id : String): List[Actor] = synchronized {
    actorsById.get(id) match {
      case None => Nil
      case Some(instances) => instances
    }
  }

  def register(actor: Actor) = synchronized {
    val className = actor.getClass.getName
    actorsByClassName.get(className) match {
      case Some(instances) => actorsByClassName + (className -> (actor :: instances))
      case None => actorsByClassName + (className -> (actor :: Nil))
    }
    val id = actor.getId
    if (id eq null) throw new IllegalStateException("Actor.id is null " + actor)
    actorsById.get(id) match {
      case Some(instances) => actorsById + (id -> (actor :: instances))
      case None => actorsById + (id -> (actor :: Nil))
    }
  }

  def unregister(actor: Actor) = synchronized {
    actorsByClassName - actor.getClass.getName
    actorsById - actor.getId
  }

  def shutdownAll = {
    log.info("Shutting down all actors in the system...")
    actorsById.foreach(entry => entry._2.map(_.stop))
    log.info("All actors have been shut down")
  }
}
