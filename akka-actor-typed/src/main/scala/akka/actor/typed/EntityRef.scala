/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import scala.concurrent.Future

import akka.actor.typed.internal.InternalRecipientRef
import akka.annotation.DoNotInherit
import akka.pattern.StatusReply
import akka.util.Timeout

/**
 * A reference to an Entity, which allows `ActorRef`-like usage.
 *
 * An [[EntityRef]] is NOT an [[ActorRef]]–by design–in order to be explicit about the fact that the life-cycle
 * of an Entity is very different than a plain Actors. Most notably, this is shown by features of Entities
 * such as managed lifecycle or passivation. Both of which are aimed to be completely
 * transparent to users of such Entity. In other words, if this were to be a plain ActorRef, it would be possible to
 * apply DeathWatch to it, which in turn would then trigger when the Actor stopped, breaking the illusion that
 * Entity refs are "always there". Please note that while not encouraged, it is possible to expose an Actor's `self`
 * [[ActorRef]] and watch it in case such notification is desired.
 * Not for user extension.
 *
 */
@DoNotInherit trait EntityRef[-M] extends RecipientRef[M] { this: InternalRecipientRef[M] =>

  /**
   * The identifier for the particular entity referenced by this EntityRef.
   *
   * {{{
   * system.entityRefFor(typeKey, "someId").entityId == "someId"  // always true
   * }}}
   */
  def entityId: String

  /**
   * The EntityTypeKey associated with this EntityRef.
   */
  def typeKey: EntityTypeKey[M]

  /**
   * Send a message to the entity referenced by this EntityRef using *at-most-once*
   * messaging semantics.
   *
   * Example usage:
   * {{{
   * val target: EntityRef[String] = ...
   * target.tell("Hello")
   * }}}
   */
  def tell(msg: M): Unit

  /**
   * Send a message to the entity referenced by this EntityRef using *at-most-once*
   * messaging semantics.
   *
   * Example usage:
   * {{{
   * val target: EntityRef[String] = ...
   * target ! "Hello"
   * }}}
   */
  def !(msg: M): Unit = this.tell(msg)

  /**
   * Allows to "ask" the [[EntityRef]] for a reply.
   * See [[akka.actor.typed.scaladsl.AskPattern]] for a complete write-up of this pattern
   *
   * Note that if you are inside of an actor you should prefer [[akka.actor.typed.scaladsl.ActorContext.ask]]
   * as that provides better safety.
   *
   * Example usage:
   * {{{
   * case class Request(msg: String, replyTo: ActorRef[Reply])
   * case class Reply(msg: String)
   *
   * implicit val timeout = Timeout(3.seconds)
   * val target: EntityRef[Request] = ...
   * val f: Future[Reply] = target.ask(Request("hello", _))
   * }}}
   *
   * Please note that an implicit [[akka.util.Timeout]] must be available to use this pattern.
   *
   * @tparam Res The response protocol, what the other actor sends back
   */
  def ask[Res](f: ActorRef[Res] => M)(implicit timeout: Timeout): Future[Res]

  /**
   * The same as [[ask]] but only for requests that result in a response of type [[akka.pattern.StatusReply]].
   * If the response is a [[akka.pattern.StatusReply.Success]] the returned future is completed successfully with the wrapped response.
   * If the status response is a [[akka.pattern.StatusReply.Error]] the returned future will be failed with the
   * exception in the error (normally a [[akka.pattern.StatusReply.ErrorMessage]]).
   */
  def askWithStatus[Res](f: ActorRef[StatusReply[Res]] => M)(implicit timeout: Timeout): Future[Res]

  /**
   * Allows to "ask" the [[EntityRef]] for a reply.
   * See [[akka.actor.typed.scaladsl.AskPattern]] for a complete write-up of this pattern
   *
   * Note that if you are inside of an actor you should prefer [[akka.actor.typed.scaladsl.ActorContext.ask]]
   * as that provides better safety.
   *
   * Example usage:
   * {{{
   * case class Request(msg: String, replyTo: ActorRef[Reply])
   * case class Reply(msg: String)
   *
   * implicit val timeout = Timeout(3.seconds)
   * val target: EntityRef[Request] = ...
   * val f: Future[Reply] = target ? (replyTo => Request("hello", replyTo))
   * }}}
   *
   * Please note that an implicit [[akka.util.Timeout]] must be available to use this pattern.
   *
   * Note: it is preferable to use the non-symbolic ask method as it easier allows for wildcards for
   * the `replyTo: ActorRef`.
   *
   * @tparam Res The response protocol, what the other actor sends back
   */
  def ?[Res](message: ActorRef[Res] => M)(implicit timeout: Timeout): Future[Res] =
    this.ask(message)(timeout)

}
