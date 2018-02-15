/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.testkit.typed.scaladsl

import akka.actor.typed.{ Behavior, Signal }
import akka.annotation.DoNotInherit
import akka.testkit.typed.Effect
import akka.testkit.typed.internal.BehaviorTestKitImpl

import scala.collection.immutable

object BehaviorTestKit {
  def apply[T](initialBehavior: Behavior[T], name: String): BehaviorTestKit[T] =
    new BehaviorTestKitImpl[T](name, initialBehavior)
  def apply[T](initialBehavior: Behavior[T]): BehaviorTestKit[T] =
    apply(initialBehavior, "testkit")

}

/**
 * Not for user extension. See `BehaviorTestKit.create` factory methods
 */
@DoNotInherit
trait BehaviorTestKit[T] {

  // FIXME it is weird that this is public but it is used in BehaviorSpec, could we avoid that?
  private[akka] def ctx: akka.actor.typed.ActorContext[T]

  /**
   * Requests the oldest [[Effect]] or [[akka.testkit.typed.Effect.NoEffects]] if no effects
   * have taken place. The effect is consumed, subsequent calls won't
   * will not include this effect.
   */
  def retrieveEffect(): Effect

  def childInbox[U](name: String): TestInbox[U]

  def selfInbox(): TestInbox[T]

  /**
   * Requests all the effects. The effects are consumed, subsequent calls will only
   * see new effects.
   */
  def retrieveAllEffects(): immutable.Seq[Effect]

  /**
   * Asserts that the oldest effect is the expectedEffect. Removing it from
   * further assertions.
   */
  def expectEffect(expectedEffect: Effect): Unit

  def currentBehavior: Behavior[T]
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
