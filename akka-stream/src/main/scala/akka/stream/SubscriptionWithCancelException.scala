/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import akka.annotation.DoNotInherit
import org.reactivestreams.Subscription

import scala.util.control.NoStackTrace

/**
 * Backported from Akka 2.6 to Akka 2.5. This only includes the exceptions so that they can already be used in
 * Akka 2.5 in a binary compatible way.
 */
object SubscriptionWithCancelException {

  /**
   * Not for user extension
   */
  @DoNotInherit
  sealed abstract class NonFailureCancellation extends RuntimeException with NoStackTrace
  case object NoMoreElementsNeeded extends NonFailureCancellation
  case object StageWasCompleted extends NonFailureCancellation
}
