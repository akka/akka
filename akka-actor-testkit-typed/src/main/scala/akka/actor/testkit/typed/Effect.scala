/**
 * Copyright (C) 2014-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed

import akka.annotation.DoNotInherit

/**
 * All tracked effects for the [[akka.actor.testkit.typed.scaladsl.BehaviorTestKit]] and
 * [[akka.actor.testkit.typed.javadsl.BehaviorTestKit]] must extend this type.
 *
 * Factories/types for effects are available through [[akka.actor.testkit.typed.Effects]]
 * and [[akka.actor.testkit.typed.javadsl.EffectFactory]]
 *
 * Not for user extension
 */
@DoNotInherit
abstract class Effect private[akka] ()

