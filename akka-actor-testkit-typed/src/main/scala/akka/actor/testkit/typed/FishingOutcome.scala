/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed

import akka.annotation.DoNotInherit

/**
 * Not for user extension.
 *
 * Instances are available from `FishingOutcomes` in the respective dsls: [[akka.actor.testkit.typed.scaladsl.FishingOutcomes]]
 * and [[akka.actor.testkit.typed.javadsl.FishingOutcomes]]
 */
@DoNotInherit sealed trait FishingOutcome

object FishingOutcome {

  case object Continue extends FishingOutcome
  case object ContinueAndIgnore extends FishingOutcome
  case object Complete extends FishingOutcome
  final case class Fail(error: String) extends FishingOutcome
}
