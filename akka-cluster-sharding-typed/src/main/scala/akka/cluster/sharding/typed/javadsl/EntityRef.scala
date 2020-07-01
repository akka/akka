/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.javadsl

import java.time.Duration
import java.util.concurrent.CompletionStage

import akka.actor.typed.ActorRef
import akka.actor.typed.RecipientRef
import akka.actor.typed.internal.InternalRecipientRef
import akka.annotation.DoNotInherit
import akka.annotation.InternalApi
import akka.cluster.sharding.typed.internal.TestEntityRefImpl
import akka.cluster.sharding.typed.scaladsl
import akka.japi.function.{ Function => JFunction }
import akka.util.unused

/**
 * A reference to an sharded Entity, which allows `ActorRef`-like usage.
 *
 * An [[EntityRef]] is NOT an [[ActorRef]]–by design–in order to be explicit about the fact that the life-cycle
 * of a sharded Entity is very different than a plain Actor. Most notably, this is shown by features of Entities
 * such as re-balancing (an active Entity to a different node) or passivation. Both of which are aimed to be completely
 * transparent to users of such Entity. In other words, if this were to be a plain ActorRef, it would be possible to
 * apply DeathWatch to it, which in turn would then trigger when the sharded Actor stopped, breaking the illusion that
 * Entity refs are "always there". Please note that while not encouraged, it is possible to expose an Actor's `self`
 * [[ActorRef]] and watch it in case such notification is desired.
 *
 * Not for user extension.
 */
@DoNotInherit abstract class EntityRef[M] extends RecipientRef[M] {
  scaladslSelf: scaladsl.EntityRef[M] with InternalRecipientRef[M] =>

  /**
   * Send a message to the entity referenced by this EntityRef using *at-most-once*
   * messaging semantics.
   */
  def tell(msg: M): Unit

  /**
   * Allows to "ask" the [[EntityRef]] for a reply.
   * See [[akka.actor.typed.javadsl.AskPattern]] for a complete write-up of this pattern
   *
   * Note that if you are inside of an actor you should prefer [[akka.actor.typed.javadsl.ActorContext.ask]]
   * as that provides better safety.
   *
   * @tparam Res The response protocol, what the other actor sends back
   */
  def ask[Res](message: JFunction[ActorRef[Res], M], timeout: Duration): CompletionStage[Res]

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] def asScala: scaladsl.EntityRef[M] = scaladslSelf

}

/**
 * For testing purposes this `EntityRef` can be used in place of a real [[EntityRef]].
 * It forwards all messages to the `probe`.
 */
object TestEntityRef {
  def of[M](@unused typeKey: EntityTypeKey[M], entityId: String, probe: ActorRef[M]): EntityRef[M] =
    new TestEntityRefImpl[M](entityId, probe)
}
