/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.testkit

import akka.actor._
import akka.util.ReflectiveAccess
import akka.event.EventHandler

import com.eaio.uuid.UUID

/**
 * This special ActorRef is exclusively for use during unit testing in a single-threaded environment. Therefore, it
 * overrides the dispatcher to CallingThreadDispatcher and sets the receiveTimeout to None. Otherwise,
 * it acts just like a normal ActorRef. You may retrieve a reference to the underlying actor to test internal logic.
 *
 *
 * @author Roland Kuhn
 * @since 1.1
 */
class TestActorRef[T <: Actor](factory: () ⇒ T, address: String) extends LocalActorRef(factory, address) {

  dispatcher = CallingThreadDispatcher.global
  receiveTimeout = None

  /**
   * Directly inject messages into actor receive behavior. Any exceptions
   * thrown will be available to you, while still being able to use
   * become/unbecome and their message counterparts.
   */
  def apply(o: Any) { actor(o) }

  /**
   * Retrieve reference to the underlying actor, where the static type matches the factory used inside the
   * constructor. Beware that this reference is discarded by the ActorRef upon restarting the actor (should this
   * reference be linked to a supervisor). The old Actor may of course still be used in post-mortem assertions.
   */
  def underlyingActor: T = actor.asInstanceOf[T]

  /**
   * Override to return the more specific static type.
   */
  override def start() = {
    super.start()
    this
  }

  override def toString = "TestActor[" + address + ":" + uuid + "]"

  override def equals(other: Any) =
    other.isInstanceOf[TestActorRef[_]] &&
      other.asInstanceOf[TestActorRef[_]].uuid == uuid

  /**
   * Override to check whether the new supervisor is running on the CallingThreadDispatcher,
   * as it should be. This can of course be tricked by linking before setting the dispatcher before starting the
   * supervisor, but then you just asked for trouble.
   */
  override def supervisor_=(a: Option[ActorRef]) {
    for (ref ← a) {
      if (!ref.dispatcher.isInstanceOf[CallingThreadDispatcher])
        EventHandler.warning(this, "supervisor " + ref + " does not use CallingThreadDispatcher")
    }
    super.supervisor_=(a)
  }

}

object TestActorRef {

  def apply[T <: Actor](factory: ⇒ T): TestActorRef[T] = apply[T](factory, new UUID().toString)

  def apply[T <: Actor](factory: ⇒ T, address: String): TestActorRef[T] = new TestActorRef(() ⇒ factory, address)

  def apply[T <: Actor: Manifest]: TestActorRef[T] = apply[T](new UUID().toString)

  def apply[T <: Actor: Manifest](address: String): TestActorRef[T] = new TestActorRef[T]({ () ⇒

    import ReflectiveAccess.{ createInstance, noParams, noArgs }
    createInstance[T](manifest[T].erasure, noParams, noArgs) match {
      case r: Right[_, T] ⇒ r.b
      case l: Left[Exception, _] ⇒ throw new ActorInitializationException(
        "Could not instantiate Actor" +
          "\nMake sure Actor is NOT defined inside a class/trait," +
          "\nif so put it outside the class/trait, f.e. in a companion object," +
          "\nOR try to change: 'actorOf[MyActor]' to 'actorOf(new MyActor)'.", l.a)
    }
  }, address)
}
