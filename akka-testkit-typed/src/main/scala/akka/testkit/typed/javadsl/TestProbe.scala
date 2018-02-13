/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.testkit.typed.javadsl

import akka.actor.typed.ActorSystem
import akka.testkit.typed.FishingOutcome

import scala.concurrent.duration.FiniteDuration
import scala.collection.JavaConverters._

object FishingOutcomes {

  /**
   * Consume this message and continue with the next
   */
  def continue(): FishingOutcome = akka.testkit.typed.scaladsl.FishingOutcomes.Continue

  /**
   * Complete fishing and return this message
   */
  def complete(): FishingOutcome = akka.testkit.typed.scaladsl.FishingOutcomes.Complete

  /**
   * Fail fishing with a custom error message
   */
  def fail(error: String): FishingOutcome = akka.testkit.typed.scaladsl.FishingOutcomes.Fail(error)
}

/**
 * Java API:
 */
class TestProbe[M](name: String, system: ActorSystem[_]) extends akka.testkit.typed.scaladsl.TestProbe[M](name)(system) {

  def this(system: ActorSystem[_]) = this("testProbe", system)

  /**
   * Same as `expectMsgType[T](remainingOrDefault)`, but correctly treating the timeFactor.
   */
  def expectMessageType[T <: M](t: Class[T]): T =
    expectMessageClass_internal(remainingOrDefault, t)

  /**
   * Java API: Allows for flexible matching of multiple messages within a timeout, the fisher function is fed each incoming
   * message, and returns one of the following effects to decide on what happens next:
   *
   *  * [[FishingOutcomes.continue()]] - continue with the next message given that the timeout has not been reached
   *  * [[FishingOutcomes.complete()]] - successfully complete and return the message
   *  * [[FishingOutcomes.fail(errorMsg)]] - fail the test with a custom message
   *
   * Additionally failures includes the list of messages consumed. If a message of type `M` but not of type `T` is
   * received this will also fail the test, additionally if the `fisher` function throws a match error the error
   * is decorated with some fishing details and the test is failed (making it convenient to use this method with a
   * partial function).
   *
   * @param max Max total time without the fisher function returning `CompleteFishing` before failing
   *            The timeout is dilated.
   * @return The messages accepted in the order they arrived
   */
  // FIXME same name would cause ambiguity but I'm out of ideas how to fix, separate Scala/Java TestProbe APIs?
  def fishForMessageJava(max: FiniteDuration, fisher: java.util.function.Function[M, FishingOutcome]): java.util.List[M] =
    fishForMessage(max)(fisher.apply).asJava

  /**
   * Same as the other `fishForMessageJava` but includes the provided hint in all error messages
   */
  def fishForMessageJava(max: FiniteDuration, hint: String, fisher: java.util.function.Function[M, FishingOutcome]): java.util.List[M] =
    fishForMessage(max, hint)(fisher.apply).asJava

}
