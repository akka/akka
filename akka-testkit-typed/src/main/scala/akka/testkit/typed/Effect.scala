/**
 * Copyright (C) 2014-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.testkit.typed

import akka.annotation.DoNotInherit

/**
 * All tracked effects for the [[akka.testkit.typed.scaladsl.BehaviorTestKit]] and
 * [[akka.testkit.typed.javadsl.BehaviorTestKit]] must extend this type.
 *
 * Factories/types for effects are available through [[akka.testkit.typed.scaladsl.Effects]]
 * and [[akka.testkit.typed.javadsl.Effects]]
 *
 * Not for user extension
 */
@DoNotInherit
abstract class Effect private[akka] ()

