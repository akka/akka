/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.testkit

import akka.actor._
import akka.util.ReflectiveAccess
import akka.event.EventHandler

import com.eaio.uuid.UUID
import akka.actor.Props._

/**
 * This special ActorRef is exclusively for use during unit testing in a single-threaded environment. Therefore, it
 * overrides the dispatcher to CallingThreadDispatcher and sets the receiveTimeout to None. Otherwise,
 * it acts just like a normal ActorRef. You may retrieve a reference to the underlying actor to test internal logic.
 *
 * @author Roland Kuhn
 * @since 1.1
 */
class TestActorRef[T <: Actor](props: Props, address: String) extends LocalActorRef(props.withDispatcher(CallingThreadDispatcher.global), address, false) {
  /**
   * Directly inject messages into actor receive behavior. Any exceptions
   * thrown will be available to you, while still being able to use
   * become/unbecome and their message counterparts.
   */
  def apply(o: Any) { underlyingActorInstance.apply(o) }

  /**
   * Retrieve reference to the underlying actor, where the static type matches the factory used inside the
   * constructor. Beware that this reference is discarded by the ActorRef upon restarting the actor (should this
   * reference be linked to a supervisor). The old Actor may of course still be used in post-mortem assertions.
   */
  def underlyingActor: T = underlyingActorInstance.asInstanceOf[T]

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
    a match { //TODO This should probably be removed since the Supervisor could be a remote actor for all we know
      case Some(l: LocalActorRef) if !l.underlying.dispatcher.isInstanceOf[CallingThreadDispatcher] ⇒
        EventHandler.warning(this, "supervisor " + l + " does not use CallingThreadDispatcher")
      case _ ⇒
    }
    super.supervisor_=(a)
  }

}

object TestActorRef {

  def apply[T <: Actor](factory: ⇒ T): TestActorRef[T] = apply[T](Props(factory), new UUID().toString)

  def apply[T <: Actor](factory: ⇒ T, address: String): TestActorRef[T] = apply[T](Props(factory), address)

  def apply[T <: Actor](props: Props): TestActorRef[T] = apply[T](props, new UUID().toString)

  def apply[T <: Actor](props: Props, address: String): TestActorRef[T] = new TestActorRef(props, address)

  def apply[T <: Actor: Manifest]: TestActorRef[T] = apply[T](new UUID().toString)

  def apply[T <: Actor: Manifest](address: String): TestActorRef[T] = apply[T](Props({
    import ReflectiveAccess.{ createInstance, noParams, noArgs }
    createInstance[T](manifest[T].erasure, noParams, noArgs) match {
      case Right(value) ⇒ value
      case Left(exception) ⇒ throw new ActorInitializationException(
        "Could not instantiate Actor" +
          "\nMake sure Actor is NOT defined inside a class/trait," +
          "\nif so put it outside the class/trait, f.e. in a companion object," +
          "\nOR try to change: 'actorOf[MyActor]' to 'actorOf(new MyActor)'.", exception)
    }
  }), address)
}
