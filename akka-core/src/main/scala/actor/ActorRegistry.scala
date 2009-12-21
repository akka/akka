/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.actor

import se.scalablesolutions.akka.util.Logging

import scala.collection.mutable.HashMap

/**
 * Registry holding all actor instances, mapped by class and the actor's id field (which can be set by user-code).
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object ActorRegistry extends Logging {
  private val actorsByClassName = new HashMap[String, List[Actor]]
  private val actorsById = new HashMap[String, List[Actor]]

  def actorsFor(clazz: Class[_ <: Actor]): List[Actor] = synchronized {
    actorsByClassName.get(clazz.getName) match {
      case None => Nil
      case Some(instances) => instances
    }
  }

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
    val id = actor.id
    if (id == null) throw new IllegalStateException("Actor.id is null " + actor)
    actorsById.get(id) match {
      case Some(instances) => actorsById + (id -> (actor :: instances))
      case None => actorsById + (id -> (actor :: Nil))
    }
  }

  def unregister(actor: Actor) = synchronized {
    actorsByClassName - actor.getClass.getName
    actorsById - actor.getClass.getName
  }

  // TODO: document ActorRegistry.shutdownAll
  def shutdownAll = {
    log.info("Shutting down all actors in the system...")
    actorsById.foreach(entry => entry._2.map(_.stop))
    log.info("All actors have been shut down")
  }
}
