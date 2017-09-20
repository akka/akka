/*
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */

package akka.typed.cluster

import akka.actor.NoSerializationVerificationNeeded

import scala.concurrent.duration.FiniteDuration

// FIXME do the real thing
final class ClusterSingletonManagerSettings(
  val singletonName:         String,
  val role:                  Option[String],
  val removalMargin:         FiniteDuration,
  val handOverRetryInterval: FiniteDuration) extends NoSerializationVerificationNeeded {
}
