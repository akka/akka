/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import akka.japi.Creator
import akka.util.Timeout

/**
 * Migration replacement for `object akka.actor.Actor`.
 */
@deprecated("use ActorRefFactory (ActorSystem or ActorContext) to create actors", "2.0")
object OldActor {

  /**
   *  Creates an ActorRef out of the Actor with type T.
   *  It will be automatically started, i.e. remove old call to `start()`.
   *
   */
  @deprecated("use ActorRefFactory (ActorSystem or ActorContext) to create actors", "2.0")
  def actorOf[T <: Actor: Manifest]: ActorRef = actorOf(manifest[T].erasure.asInstanceOf[Class[_ <: Actor]])

  /**
   * Creates an ActorRef out of the Actor of the specified Class.
   * It will be automatically started, i.e. remove old call to `start()`.
   */
  @deprecated("use ActorRefFactory (ActorSystem or ActorContext) to create actors", "2.0")
  def actorOf(clazz: Class[_ <: Actor]): ActorRef = GlobalActorSystem.actorOf(Props(clazz))

  /**
   * Creates an ActorRef out of the Actor. Allows you to pass in a factory function
   * that creates the Actor. Please note that this function can be invoked multiple
   * times if for example the Actor is supervised and needs to be restarted.
   *
   * It will be automatically started, i.e. remove old call to `start()`.
   */
  @deprecated("use ActorRefFactory (ActorSystem or ActorContext) to create actors", "2.0")
  def actorOf(factory: ⇒ Actor): ActorRef = GlobalActorSystem.actorOf(Props(factory))

  /**
   * Creates an ActorRef out of the Actor. Allows you to pass in a factory (Creator<Actor>)
   * that creates the Actor. Please note that this function can be invoked multiple
   * times if for example the Actor is supervised and needs to be restarted.
   * <p/>
   * JAVA API
   */
  @deprecated("use ActorRefFactory (ActorSystem or ActorContext) to create actors", "2.0")
  def actorOf(creator: Creator[Actor]): ActorRef = GlobalActorSystem.actorOf(Props(creator))

}

@deprecated("use Actor", "2.0")
abstract class OldActor extends Actor {

  implicit def askTimeout: Timeout = context.system.settings.ActorTimeout

}