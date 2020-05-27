/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed
package javadsl

import java.time.Duration
import java.util.concurrent.CompletionStage

import scala.compat.java8.FutureConverters._

import akka.actor.typed.Scheduler
import akka.actor.typed.scaladsl.AskPattern._
import akka.japi.function.{ Function => JFunction }
import akka.util.JavaDurationConverters._

/**
 * The ask-pattern implements the initiator side of a request–reply protocol.
 *
 * Note that if you are inside of an actor you should prefer [[ActorContext.ask]]
 * as that provides better safety.
 *
 * The party that asks may be within or without an Actor, since the
 * implementation will fabricate a (hidden) [[ActorRef]] that is bound to a
 * `CompletableFuture`. This ActorRef will need to be injected in the
 * message that is sent to the target Actor in order to function as a reply-to
 * address, therefore the argument to the ask method is not the message itself
 * but a function that given the reply-to address will create the message.
 *
 */
object AskPattern {

  /**
   * @tparam Req The request protocol, what the other actor accepts
   * @tparam Res The response protocol, what the other actor sends back
   */
  def ask[Req, Res](
      actor: RecipientRef[Req],
      messageFactory: JFunction[ActorRef[Res], Req],
      timeout: Duration,
      scheduler: Scheduler): CompletionStage[Res] =
    (actor.ask(messageFactory.apply)(timeout.asScala, scheduler)).toJava
}
