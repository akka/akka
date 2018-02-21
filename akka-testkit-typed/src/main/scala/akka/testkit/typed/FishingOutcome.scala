/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.testkit.typed

import akka.annotation.DoNotInherit

/**
 * Not for user extension.
 *
 * Instances are available from `FishingOutcomes` in the respective dsls: [[akka.testkit.typed.scaladsl.FishingOutcomes]]
 * and [[akka.testkit.typed.javadsl.FishingOutcomes]]
 */
@DoNotInherit sealed trait FishingOutcome

object FishingOutcome {

  case object Continue extends FishingOutcome
  case object ContinueAndIgnore extends FishingOutcome
  case object Complete extends FishingOutcome
  case class Fail(error: String) extends FishingOutcome
}
