/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.actor.typed.scaladsl

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.FiniteDuration
import akka.annotation.ApiMayChange
import akka.annotation.DoNotInherit
import akka.actor.typed._
import akka.util.Timeout

import scala.reflect.ClassTag
import scala.util.Try

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
 * created, access to [[Terminated]] and timed message scheduling.
 */
@DoNotInherit
@ApiMayChange
trait ActorContext[T] { this: akka.actor.typed.javadsl.ActorContext[T] ⇒

  /**
   * Get the `javadsl` of this `ActorContext`.
   */
  def asJava: akka.actor.typed.javadsl.ActorContext[T]

  /**
   * The identity of this Actor, bound to the lifecycle of this Actor instance.
   * An Actor with the same name that lives before or after this instance will
   * have a different [[ActorRef]].
   */
  def self: ActorRef[T]

  /**
   * Return the mailbox capacity that was configured by the parent for this actor.
   */
  def mailboxCapacity: Int

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
   * Create a child Actor from the given [[akka.actor.typed.Behavior]] under a randomly chosen name.
   * It is good practice to name Actors wherever practical.
   */
  def spawnAnonymous[U](behavior: Behavior[U], props: Props = Props.empty): ActorRef[U]

  /**
   * Create a child Actor from the given [[akka.actor.typed.Behavior]] and with the given name.
   */
  def spawn[U](behavior: Behavior[U], name: String, props: Props = Props.empty): ActorRef[U]

  /**
   * Force the child Actor under the given name to terminate after it finishes
   * processing its current message. Nothing happens if the ActorRef does not
   * refer to a current child actor.
   *
   * @return whether the passed-in [[ActorRef]] points to a current child Actor
   */
  def stop[U](child: ActorRef[U]): Boolean

  /**
   * Register for [[akka.actor.typed.Terminated]] notification once the Actor identified by the
   * given [[ActorRef]] terminates. This message is also sent when the watched actor
   * is on a node that has been removed from the cluster when using akka-cluster
   * or has been marked unreachable when using akka-remote directly
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
  implicit def executionContext: ExecutionContextExecutor

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
   *
   * For a single use request-response adapter see [[ask]].
   */
  def spawnAdapter[U](f: U ⇒ T, name: String): ActorRef[U]

  /**
   * Create an anonymous child actor that will wrap messages such that other Actor’s
   * protocols can be ingested by this Actor. You are strongly advised to cache
   * these ActorRefs or to stop them when no longer needed.
   *
   * For a single use request-response adapter see [[ask]].
   */
  def spawnAdapter[U](f: U ⇒ T): ActorRef[U]

  /**
   * Perform a single request-response message interaction with another actor, and transform the messages back to
   * the protocol of this actor.
   *
   * The interaction has a timeout (to avoid resource a resource leak). If the timeout hits without any response it
   * will be transformed to a message for this actor through the `failToOwnProtocol` function (this is the only
   * "normal" way a `Failure` is passed to the function).
   *
   * For other messaging patterns with other actors, see [[spawnAdapter]].
   *
   * @param createMessage A function that creates a message for the other actor, containing the provided `ActorRef[Res]` that
   *                      the other actor can send a message back through.
   * @param responseToOwnProtocol Transforms the response from the `otherActor` into a message this actor understands.
   *                              Can touch immutable state to provide a context for the interaction, an id for example, but
   *                              must not touch the `ActorContext` as it will not be executed inside of this actor.
   *                              Will be fed a `Failure(AskTimeoutException)` if the response timeout hits.
   *
   * @tparam Req The request protocol, what the other actor accepts
   * @tparam Res The response protocol, what the other actor sends back
   */
  def ask[Req, Res](
    otherActor:    ActorRef[Req],
    createMessage: ActorRef[Res] ⇒ Req
  )(responseToOwnProtocol: Try[Res] ⇒ T)(implicit responseTimeout: Timeout, classTag: ClassTag[Res]): Unit

}
