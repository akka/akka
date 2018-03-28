/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.testkit.typed.scaladsl

import akka.actor.typed.{ Behavior, Signal }
import akka.annotation.DoNotInherit
import akka.testkit.typed.Effect
import akka.testkit.typed.internal.BehaviorTestKitImpl

import scala.collection.immutable
import scala.reflect.ClassTag

object BehaviorTestKit {
  def apply[T](initialBehavior: Behavior[T], name: String): BehaviorTestKit[T] =
    new BehaviorTestKitImpl[T](name, initialBehavior)
  def apply[T](initialBehavior: Behavior[T]): BehaviorTestKit[T] =
    apply(initialBehavior, "testkit")

}

/**
 * Used for synchronous testing [[akka.actor.typed.Behavior]]s. Stores all effects e.g. Spawning of children,
 * watching and offers access to what effects have taken place.
 *
 * For asynchronous testing of `Behavior`s running see [[ActorTestKit]]
 *
 * Not for user extension. See `BehaviorTestKit.apply` factory methods
 */
@DoNotInherit
trait BehaviorTestKit[T] {

  // FIXME it is weird that this is public but it is used in BehaviorSpec, could we avoid that?
  private[akka] def ctx: akka.actor.typed.ActorContext[T]

  /**
   * Requests the oldest [[Effect]] or [[akka.testkit.typed.scaladsl.Effects.NoEffects]] if no effects
   * have taken place. The effect is consumed, subsequent calls won't
   * will not include this effect.
   */
  def retrieveEffect(): Effect

  /**
   * Get the child inbox for the child with the given name, or fail if there is no child with the given name
   * spawned
   */
  def childInbox[U](name: String): TestInbox[U]

  /**
   * The self inbox contains messages the behavior sent to `ctx.self`
   */
  def selfInbox(): TestInbox[T]

  /**
   * Requests all the effects. The effects are consumed, subsequent calls will only
   * see new effects.
   */
  def retrieveAllEffects(): immutable.Seq[Effect]

  /**
   * Returns if there have been any effects.
   */
  def hasEffects(): Boolean

  /**
   * Asserts that the oldest effect is the expectedEffect. Removing it from
   * further assertions.
   */
  def expectEffect(expectedEffect: Effect): Unit

  /**
   * Asserts that the oldest effect is of type T. Consumes and returns the concrete effect for
   * further direct assertions.
   */
  def expectEffectType[E <: Effect](implicit classTag: ClassTag[E]): E

  /**
   * Asserts that the oldest effect matches the given partial function.
   */
  def expectEffectPF[R](f: PartialFunction[Effect, R]): R

  /**
   * The current behavior, can change any time `run` is called
   */
  def currentBehavior: Behavior[T]

  /**
   * Returns the current behavior as it was returned from processing the previous message.
   * For example if [[Behavior.unhandled]] is returned it will be kept here, but not in
   * [[currentBehavior]].
   */
  def returnedBehavior: Behavior[T]

  /**
   * Is the current behavior alive or stopped
   */
  def isAlive: Boolean

  /**
   * Send the msg to the behavior and record any [[Effect]]s
   */
  def run(msg: T): Unit

  /**
   * Send the signal to the beheavior and record any [[Effect]]s
   */
  def signal(signal: Signal): Unit
}
