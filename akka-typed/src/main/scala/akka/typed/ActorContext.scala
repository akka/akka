/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed

import scala.concurrent.duration.Duration
import scala.collection.immutable.TreeMap
import akka.util.Helpers
import akka.{ actor ⇒ untyped }
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.ExecutionContextExecutor

/**
 * An Actor is given by the combination of a [[Behavior]] and a context in
 * which this behavior is executed. As per the Actor Model an Actor can perform
 * the following actions when processing a message:
 *
 *  - send a finite number of messages to other Actors it knows
 *  - create a finite number of Actors
 *  - designate the behavior for the next message
 *
 * In Akka the first capability is accessed by using the `!` or `tell` method
 * on an [[ActorRef]], the second is provided by [[ActorContext#spawn]]
 * and the third is implicit in the signature of [[Behavior]] in that the next
 * behavior is always returned from the message processing logic.
 *
 * An `ActorContext` in addition provides access to the Actor’s own identity (“`self`”),
 * the [[ActorSystem]] it is part of, methods for querying the list of child Actors it
 * created, access to [[Terminated DeathWatch]] and timed message scheduling.
 */
trait ActorContext[T] {

  /**
   * The identity of this Actor, bound to the lifecycle of this Actor instance.
   * An Actor with the same name that lives before or after this instance will
   * have a different [[ActorRef]].
   */
  def self: ActorRef[T]

  /**
   * The [[Props]] from which this Actor was created.
   */
  def props: Props[T]

  /**
   * The [[ActorSystem]] to which this Actor belongs.
   */
  def system: ActorSystem[Nothing]

  /**
   * The list of child Actors created by this Actor during its lifetime that
   * are still alive, in no particular order.
   */
  def children: Iterable[ActorRef[Nothing]]

  /**
   * The named child Actor if it is alive.
   */
  def child(name: String): Option[ActorRef[Nothing]]

  /**
   * Create a child Actor from the given [[Props]] under a randomly chosen name.
   * It is good practice to name Actors wherever practical.
   */
  def spawnAnonymous[U](props: Props[U]): ActorRef[U]

  /**
   * Create a child Actor from the given [[Props]] and with the given name.
   */
  def spawn[U](props: Props[U], name: String): ActorRef[U]

  /**
   * Create an untyped child Actor from the given [[akka.actor.Props]] under a randomly chosen name.
   * It is good practice to name Actors wherever practical.
   */
  def actorOf(props: untyped.Props): untyped.ActorRef

  /**
   * Create an untyped child Actor from the given [[akka.actor.Props]] and with the given name.
   */
  def actorOf(props: untyped.Props, name: String): untyped.ActorRef

  /**
   * Force the child Actor under the given name to terminate after it finishes
   * processing its current message. Nothing happens if the ActorRef does not
   * refer to a current child actor.
   *
   * @return whether the passed-in [[ActorRef]] points to a current child Actor
   */
  def stop(child: ActorRef[Nothing]): Boolean

  /**
   * Register for [[Terminated]] notification once the Actor identified by the
   * given [[ActorRef]] terminates. This notification is also generated when the
   * [[ActorSystem]] to which the referenced Actor belongs is declared as
   * failed (e.g. in reaction to being unreachable).
   */
  def watch[U](other: ActorRef[U]): ActorRef[U]

  /**
   * Register for [[Terminated]] notification once the Actor identified by the
   * given [[akka.actor.ActorRef]] terminates. This notification is also generated when the
   * [[ActorSystem]] to which the referenced Actor belongs is declared as
   * failed (e.g. in reaction to being unreachable).
   */
  def watch(other: akka.actor.ActorRef): akka.actor.ActorRef

  /**
   * Revoke the registration established by `watch`. A [[Terminated]]
   * notification will not subsequently be received for the referenced Actor.
   */
  def unwatch[U](other: ActorRef[U]): ActorRef[U]

  /**
   * Revoke the registration established by `watch`. A [[Terminated]]
   * notification will not subsequently be received for the referenced Actor.
   */
  def unwatch(other: akka.actor.ActorRef): akka.actor.ActorRef

  /**
   * Schedule the sending of a [[ReceiveTimeout]] notification in case no other
   * message is received during the given period of time. The timeout starts anew
   * with each received message. Provide `Duration.Undefined` to switch off this
   * mechanism.
   */
  def setReceiveTimeout(d: Duration): Unit

  /**
   * Schedule the sending of the given message to the given target Actor after
   * the given time period has elapsed. The scheduled action can be cancelled
   * by invoking [[akka.actor.Cancellable]] `cancel` on the returned
   * handle.
   */
  def schedule[U](delay: FiniteDuration, target: ActorRef[U], msg: U): untyped.Cancellable

  /**
   * This Actor’s execution context. It can be used to run asynchronous tasks
   * like [[scala.concurrent.Future]] combinators.
   */
  implicit def executionContext: ExecutionContextExecutor

  /**
   * Create a child actor that will wrap messages such that other Actor’s
   * protocols can be ingested by this Actor. You are strongly advised to cache
   * these ActorRefs or to stop them when no longer needed.
   */
  def spawnAdapter[U](f: U ⇒ T): ActorRef[U]
}

/**
 * An [[ActorContext]] for synchronous execution of a [[Behavior]] that
 * provides only stubs for the effects an Actor can perform and replaces
 * created child Actors by a synchronous Inbox (see `Inbox.sync`).
 *
 * See [[EffectfulActorContext]] for more advanced uses.
 */
class StubbedActorContext[T](
  val name:           String,
  override val props: Props[T])(
  override implicit val system: ActorSystem[Nothing]) extends ActorContext[T] {

  val inbox = Inbox.sync[T](name)
  override val self = inbox.ref

  private var _children = TreeMap.empty[String, Inbox.SyncInbox[_]]
  private val childName = Iterator from 1 map (Helpers.base64(_))

  override def children: Iterable[ActorRef[Nothing]] = _children.values map (_.ref)
  override def child(name: String): Option[ActorRef[Nothing]] = _children get name map (_.ref)
  override def spawnAnonymous[U](props: Props[U]): ActorRef[U] = {
    val i = Inbox.sync[U](childName.next())
    _children += i.ref.untypedRef.path.name → i
    i.ref
  }
  override def spawn[U](props: Props[U], name: String): ActorRef[U] =
    _children get name match {
      case Some(_) ⇒ throw new untyped.InvalidActorNameException(s"actor name $name is already taken")
      case None ⇒
        val i = Inbox.sync[U](name)
        _children += name → i
        i.ref
    }
  override def actorOf(props: untyped.Props): untyped.ActorRef = {
    val i = Inbox.sync[Any](childName.next())
    _children += i.ref.untypedRef.path.name → i
    i.ref.untypedRef
  }
  override def actorOf(props: untyped.Props, name: String): untyped.ActorRef =
    _children get name match {
      case Some(_) ⇒ throw new untyped.InvalidActorNameException(s"actor name $name is already taken")
      case None ⇒
        val i = Inbox.sync[Any](name)
        _children += name → i
        i.ref.untypedRef
    }
  override def stop(child: ActorRef[Nothing]): Boolean = {
    // removal is asynchronous, so don’t do it here; explicit removeInbox needed from outside
    _children.get(child.path.name) match {
      case None        ⇒ false
      case Some(inbox) ⇒ inbox.ref == child
    }
  }
  def watch[U](other: ActorRef[U]): ActorRef[U] = other
  def watch(other: akka.actor.ActorRef): other.type = other
  def unwatch[U](other: ActorRef[U]): ActorRef[U] = other
  def unwatch(other: akka.actor.ActorRef): other.type = other
  def setReceiveTimeout(d: Duration): Unit = ()

  def schedule[U](delay: FiniteDuration, target: ActorRef[U], msg: U): untyped.Cancellable = new untyped.Cancellable {
    def cancel() = false
    def isCancelled = true
  }
  implicit def executionContext: ExecutionContextExecutor = system.executionContext
  def spawnAdapter[U](f: U ⇒ T): ActorRef[U] = ???

  def getInbox[U](name: String): Inbox.SyncInbox[U] = _children(name).asInstanceOf[Inbox.SyncInbox[U]]
  def removeInbox(name: String): Unit = _children -= name
}

/*
 * TODO
 * 
 * Currently running a behavior requires that the context stays the same, since
 * the behavior may well close over it and thus a change might not be effective
 * at all. Another issue is that there is genuine state within the context that
 * is coupled to the behavior’s state: if child actors were created then
 * migrating a behavior into a new context will not work.
 * 
 * This note is about remembering the reasons behind this restriction and
 * proposes an ActorContextProxy as a (broken) half-solution. Another avenue
 * by which a solution may be explored is for Pure behaviors in that they
 * may be forced to never remember anything that is immobile.
 */
//class MobileActorContext[T](_name: String, _props: Props[T], _system: ActorSystem[Nothing])
//  extends EffectfulActorContext[T](_name, _props, _system) {
//
//}
//
//class ActorContextProxy[T](var d: ActorContext[T]) extends ActorContext[T]
