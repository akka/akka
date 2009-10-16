/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.actor

import se.scalablesolutions.akka.util.Logging

import scala.collection.mutable.HashMap

/**
 * Registry holding all actor instances, mapped by class.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object ActorRegistry extends Logging {
  private val actors = new HashMap[String, List[Actor]]

  def actorsFor(clazz: Class[_]): List[Actor] = actorsFor(clazz.getName)

  def actorsFor(fqn : String): List[Actor] = synchronized {
    actors.get(fqn) match {
      case None => Nil
      case Some(instances) => instances
    }
  }
 
  def register(actor: Actor) = synchronized {
    val name = actor.getClass.getName
    actors.get(name) match {
      case Some(instances) => actors + (name -> (actor :: instances))
      case None => actors + (name -> (actor :: Nil))
    }
  }
}
