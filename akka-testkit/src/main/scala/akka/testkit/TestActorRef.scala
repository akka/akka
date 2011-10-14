/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.testkit

import akka.actor._
import akka.util.ReflectiveAccess
import akka.event.EventHandler
import com.eaio.uuid.UUID
import akka.actor.Props._
import akka.AkkaApplication

/**
 * This special ActorRef is exclusively for use during unit testing in a single-threaded environment. Therefore, it
 * overrides the dispatcher to CallingThreadDispatcher and sets the receiveTimeout to None. Otherwise,
 * it acts just like a normal ActorRef. You may retrieve a reference to the underlying actor to test internal logic.
 *
 * @author Roland Kuhn
 * @since 1.1
 */
class TestActorRef[T <: Actor](_app: AkkaApplication, props: Props, address: String)
  extends LocalActorRef(_app, props.withDispatcher(new CallingThreadDispatcher(_app)), address, false) {
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

  override def equals(other: Any) = other.isInstanceOf[TestActorRef[_]] && other.asInstanceOf[TestActorRef[_]].uuid == uuid
}

object TestActorRef {

  def apply[T <: Actor](factory: ⇒ T)(implicit app: AkkaApplication): TestActorRef[T] = apply[T](Props(factory), new UUID().toString)

  def apply[T <: Actor](factory: ⇒ T, address: String)(implicit app: AkkaApplication): TestActorRef[T] = apply[T](Props(factory), address)

  def apply[T <: Actor](props: Props)(implicit app: AkkaApplication): TestActorRef[T] = apply[T](props, new UUID().toString)

  def apply[T <: Actor](props: Props, address: String)(implicit app: AkkaApplication): TestActorRef[T] = new TestActorRef(app, props, address)

  def apply[T <: Actor](implicit m: Manifest[T], app: AkkaApplication): TestActorRef[T] = apply[T](new UUID().toString)

  def apply[T <: Actor](address: String)(implicit m: Manifest[T], app: AkkaApplication): TestActorRef[T] = apply[T](Props({
    import ReflectiveAccess.{ createInstance, noParams, noArgs }
    createInstance[T](m.erasure, noParams, noArgs) match {
      case Right(value) ⇒ value
      case Left(exception) ⇒ throw new ActorInitializationException(
        "Could not instantiate Actor" +
          "\nMake sure Actor is NOT defined inside a class/trait," +
          "\nif so put it outside the class/trait, f.e. in a companion object," +
          "\nOR try to change: 'actorOf[MyActor]' to 'actorOf(new MyActor)'.", exception)
    }
  }), address)
}
