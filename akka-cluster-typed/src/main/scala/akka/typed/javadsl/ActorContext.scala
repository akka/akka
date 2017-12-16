/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.javadsl

import java.util.function.{ Function ⇒ JFunction }
import akka.annotation.DoNotInherit
import akka.annotation.ApiMayChange
import akka.typed.ActorRef
import akka.typed.ActorSystem
import java.util.Optional
import akka.typed.Behavior
import akka.typed.Props
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
 * In Akka the first capability is accessed by using the `tell` method
 * on an [[ActorRef]], the second is provided by [[ActorContext#spawn]]
 * and the third is implicit in the signature of [[Behavior]] in that the next
 * behavior is always returned from the message processing logic.
 *
 * An `ActorContext` in addition provides access to the Actor’s own identity (“`getSelf`”),
 * the [[ActorSystem]] it is part of, methods for querying the list of child Actors it
 * created, access to [[Terminated DeathWatch]] and timed message scheduling.
 */
@DoNotInherit
@ApiMayChange
trait ActorContext[T] {
  // this must be a pure interface, i.e. only abstract methods

  /**
   * Get the `scaladsl` of this `ActorContext`.
   */
  def asScala: akka.typed.scaladsl.ActorContext[T]

  /**
   * The identity of this Actor, bound to the lifecycle of this Actor instance.
   * An Actor with the same name that lives before or after this instance will
   * have a different [[ActorRef]].
   */
  def getSelf: ActorRef[T]

  /**
   * Return the mailbox capacity that was configured by the parent for this actor.
   */
  def getMailboxCapacity: Int

  /**
   * The [[ActorSystem]] to which this Actor belongs.
   */
  def getSystem: ActorSystem[Void]

  /**
   * The list of child Actors created by this Actor during its lifetime that
   * are still alive, in no particular order.
   */
  def getChildren: java.util.List[ActorRef[Void]]

  /**
   * The named child Actor if it is alive.
   */
  def getChild(name: String): Optional[ActorRef[Void]]

  /**
   * Create a child Actor from the given [[akka.typed.Behavior]] under a randomly chosen name.
   * It is good practice to name Actors wherever practical.
   */
  def spawnAnonymous[U](behavior: Behavior[U]): ActorRef[U]

  /**
   * Create a child Actor from the given [[akka.typed.Behavior]] under a randomly chosen name.
   * It is good practice to name Actors wherever practical.
   */
  def spawnAnonymous[U](behavior: Behavior[U], props: Props): ActorRef[U]

  /**
   * Create a child Actor from the given [[akka.typed.Behavior]] and with the given name.
   */
  def spawn[U](behavior: Behavior[U], name: String): ActorRef[U]

  /**
   * Create a child Actor from the given [[akka.typed.Behavior]] and with the given name.
   */
  def spawn[U](behavior: Behavior[U], name: String, props: Props): ActorRef[U]

  /**
   * Force the child Actor under the given name to terminate after it finishes
   * processing its current message. Nothing happens if the ActorRef does not
   * refer to a current child actor.
   *
   * @return whether the passed-in [[ActorRef]] points to a current child Actor
   */
  def stop[U](child: ActorRef[U]): Boolean

  /**
   * Register for [[Terminated]] notification once the Actor identified by the
   * given [[ActorRef]] terminates. This message is also sent when the watched actor
   * is on a node that has been removed from the cluster when using akka-cluster
   * or has been marked unreachable when using akka-remote directly.
   */
  def watch[U](other: ActorRef[U]): Unit

  /**
   * Register for termination notification with a custom message once the Actor identified by the
   * given [[ActorRef]] terminates. This message is also sent when the watched actor
   * is on a node that has been removed from the cluster when using akka-cluster
   * or has been marked unreachable when using akka-remote directly.
   */
  def watchWith[U](other: ActorRef[U], msg: T): Unit

  /**
   * Revoke the registration established by `watch`. A [[Terminated]]
   * notification will not subsequently be received for the referenced Actor.
   */
  def unwatch[U](other: ActorRef[U]): Unit

  /**
   * Schedule the sending of a notification in case no other
   * message is received during the given period of time. The timeout starts anew
   * with each received message. Provide `Duration.Undefined` to switch off this
   * mechanism.
   */
  def setReceiveTimeout(d: FiniteDuration, msg: T): Unit

  /**
   * Cancel the sending of receive timeout notifications.
   */
  def cancelReceiveTimeout(): Unit

  /**
   * Schedule the sending of the given message to the given target Actor after
   * the given time period has elapsed. The scheduled action can be cancelled
   * by invoking [[akka.actor.Cancellable#cancel]] on the returned
   * handle.
   */
  def schedule[U](delay: FiniteDuration, target: ActorRef[U], msg: U): akka.actor.Cancellable

  /**
   * This Actor’s execution context. It can be used to run asynchronous tasks
   * like [[scala.concurrent.Future]] combinators.
   */
  def getExecutionContext: ExecutionContextExecutor

  /**
   * Create a child actor that will wrap messages such that other Actor’s
   * protocols can be ingested by this Actor. You are strongly advised to cache
   * these ActorRefs or to stop them when no longer needed.
   *
   * The name of the child actor will be composed of a unique identifier
   * starting with a dollar sign to which the given `name` argument is
   * appended, with an inserted hyphen between these two parts. Therefore
   * the given `name` argument does not need to be unique within the scope
   * of the parent actor.
   */
  def spawnAdapter[U](f: JFunction[U, T], name: String): ActorRef[U]

  /**
   * Create an anonymous child actor that will wrap messages such that other Actor’s
   * protocols can be ingested by this Actor. You are strongly advised to cache
   * these ActorRefs or to stop them when no longer needed.
   */
  def spawnAdapter[U](f: JFunction[U, T]): ActorRef[U]

}
