/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.testkit

import akka.actor._
import java.util.concurrent.atomic.AtomicLong
import akka.dispatch._
import scala.concurrent.Await
import scala.reflect.ClassTag
import akka.pattern.ask

/**
 * This special ActorRef is exclusively for use during unit testing in a single-threaded environment. Therefore, it
 * overrides the dispatcher to CallingThreadDispatcher and sets the receiveTimeout to None. Otherwise,
 * it acts just like a normal ActorRef. You may retrieve a reference to the underlying actor to test internal logic.
 *
 * @since 1.1
 */
class TestActorRef[T <: Actor](
  _system: ActorSystemImpl,
  _prerequisites: DispatcherPrerequisites,
  _props: Props,
  _supervisor: InternalActorRef,
  name: String)
  extends {
    private val disregard = _supervisor match {
      case l: LocalActorRef ⇒ l.underlying.reserveChild(name)
      case r: RepointableActorRef ⇒ r.underlying match {
        case u: UnstartedCell ⇒ throw new IllegalStateException("cannot attach a TestActor to an unstarted top-level actor, ensure that it is started by sending a message and observing the reply")
        case c: ActorCell     ⇒ c.reserveChild(name)
        case o                ⇒ _system.log.error("trying to attach child {} to unknown type of supervisor cell {}, this is not going to end well", name, o.getClass)
      }
      case s ⇒ _system.log.error("trying to attach child {} to unknown type of supervisor {}, this is not going to end well", name, s.getClass)
    }
  } with LocalActorRef(
    _system,
    _props.withDispatcher(
      if (_props.dispatcher == Dispatchers.DefaultDispatcherId) CallingThreadDispatcher.Id
      else _props.dispatcher),
    _supervisor,
    _supervisor.path / name) {

  import TestActorRef.InternalGetActor

  override def newActorCell(system: ActorSystemImpl, ref: InternalActorRef, props: Props, supervisor: InternalActorRef): ActorCell =
    new ActorCell(system, ref, props, supervisor) {
      override def autoReceiveMessage(msg: Envelope) {
        msg.message match {
          case InternalGetActor ⇒ sender ! actor
          case _                ⇒ super.autoReceiveMessage(msg)
        }
      }
    }

  /**
   * Directly inject messages into actor receive behavior. Any exceptions
   * thrown will be available to you, while still being able to use
   * become/unbecome.
   */
  def receive(o: Any): Unit = receive(o, underlying.system.deadLetters)

  /**
   * Directly inject messages into actor receive behavior. Any exceptions
   * thrown will be available to you, while still being able to use
   * become/unbecome.
   */
  def receive(o: Any, sender: ActorRef): Unit = try {
    underlying.currentMessage = Envelope(o, if (sender eq null) underlying.system.deadLetters else sender, underlying.system)
    underlying.receiveMessage(o)
  } finally underlying.currentMessage = null

  /**
   * Retrieve reference to the underlying actor, where the static type matches the factory used inside the
   * constructor. Beware that this reference is discarded by the ActorRef upon restarting the actor (should this
   * reference be linked to a supervisor). The old Actor may of course still be used in post-mortem assertions.
   */
  def underlyingActor: T = {
    // volatile mailbox read to bring in actor field
    if (isTerminated) throw new IllegalActorStateException("underlying actor is terminated")
    underlying.actor.asInstanceOf[T] match {
      case null ⇒
        val t = TestKitExtension(_system).DefaultTimeout
        Await.result(this.?(InternalGetActor)(t), t.duration).asInstanceOf[T]
      case ref ⇒ ref
    }
  }

  /**
   * Registers this actor to be a death monitor of the provided ActorRef
   * This means that this actor will get a Terminated()-message when the provided actor
   * is permanently terminated.
   *
   * @return the same ActorRef that is provided to it, to allow for cleaner invocations
   */
  def watch(subject: ActorRef): ActorRef = underlying.watch(subject)

  /**
   * Deregisters this actor from being a death monitor of the provided ActorRef
   * This means that this actor will not get a Terminated()-message when the provided actor
   * is permanently terminated.
   *
   * @return the same ActorRef that is provided to it, to allow for cleaner invocations
   */
  def unwatch(subject: ActorRef): ActorRef = underlying.unwatch(subject)

  override def toString = "TestActor[" + path + "]"

}

object TestActorRef {

  private case object InternalGetActor extends AutoReceivedMessage with PossiblyHarmful

  private val number = new AtomicLong
  private[testkit] def randomName: String = {
    val l = number.getAndIncrement()
    "$" + akka.util.Helpers.base64(l)
  }

  def apply[T <: Actor](factory: ⇒ T)(implicit system: ActorSystem): TestActorRef[T] = apply[T](Props(factory), randomName)

  def apply[T <: Actor](factory: ⇒ T, name: String)(implicit system: ActorSystem): TestActorRef[T] = apply[T](Props(factory), name)

  def apply[T <: Actor](props: Props)(implicit system: ActorSystem): TestActorRef[T] = apply[T](props, randomName)

  def apply[T <: Actor](props: Props, name: String)(implicit system: ActorSystem): TestActorRef[T] =
    apply[T](props, system.asInstanceOf[ActorSystemImpl].guardian, name)

  def apply[T <: Actor](props: Props, supervisor: ActorRef, name: String)(implicit system: ActorSystem): TestActorRef[T] = {
    new TestActorRef(system.asInstanceOf[ActorSystemImpl], system.dispatchers.prerequisites, props, supervisor.asInstanceOf[InternalActorRef], name)
  }

  def apply[T <: Actor](implicit t: ClassTag[T], system: ActorSystem): TestActorRef[T] = apply[T](randomName)

  def apply[T <: Actor](name: String)(implicit t: ClassTag[T], system: ActorSystem): TestActorRef[T] = apply[T](Props({
    system.asInstanceOf[ExtendedActorSystem].dynamicAccess.createInstanceFor[T](t.runtimeClass, Seq()).recover({
      case exception ⇒ throw ActorInitializationException(null,
        "Could not instantiate Actor" +
          "\nMake sure Actor is NOT defined inside a class/trait," +
          "\nif so put it outside the class/trait, f.e. in a companion object," +
          "\nOR try to change: 'actorOf(Props[MyActor]' to 'actorOf(Props(new MyActor)'.", exception)
    }).get
  }), name)

  /**
   * Java API
   */
  def create[T <: Actor](system: ActorSystem, props: Props, name: String): TestActorRef[T] = apply(props, name)(system)
}
