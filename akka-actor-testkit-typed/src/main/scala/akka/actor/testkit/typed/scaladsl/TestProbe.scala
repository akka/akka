/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed.scaladsl

import akka.actor.typed.{ ActorRef, ActorSystem }
import akka.annotation.DoNotInherit
import akka.actor.testkit.typed.internal.TestProbeImpl
import akka.actor.testkit.typed.{ FishingOutcome, TestKitSettings }

import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.collection.immutable

object FishingOutcomes {
  /**
   * Complete fishing and return all messages up until this
   */
  val complete: FishingOutcome = FishingOutcome.Complete
  /**
   * Consume this message, collect it into the result, and continue with the next message
   */
  val continue: FishingOutcome = FishingOutcome.Continue
  /**
   * Consume this message, but do not collect it into the result, and continue with the next message
   */
  val continueAndIgnore: FishingOutcome = FishingOutcome.ContinueAndIgnore
  /**
   * Fail fishing with a custom error message
   */
  def fail(message: String): FishingOutcome = FishingOutcome.Fail(message)
}

object TestProbe {
  def apply[M]()(implicit system: ActorSystem[_]): TestProbe[M] =
    apply(name = "testProbe")

  def apply[M](name: String)(implicit system: ActorSystem[_]): TestProbe[M] =
    new TestProbeImpl[M](name, system)

}

/**
 * Create instances through the factories in the [[TestProbe]] companion.
 *
 * A test probe is essentially a queryable mailbox which can be used in place of an actor and the received
 * messages can then be asserted
 *
 * Not for user extension
 */
@DoNotInherit trait TestProbe[M] {

  implicit protected def settings: TestKitSettings

  /**
   * ActorRef for this TestProbe
   */
  def ref: ActorRef[M]

  /**
   * Obtain time remaining for execution of the innermost enclosing `within`
   * block or missing that it returns the properly dilated default for this
   * case from settings (key "akka.actor.testkit.typed.single-expect-default").
   */
  def remainingOrDefault: FiniteDuration

  /**
   * Obtain time remaining for execution of the innermost enclosing `within`
   * block or throw an [[AssertionError]] if no `within` block surrounds this
   * call.
   */
  def remaining: FiniteDuration

  /**
   * Obtain time remaining for execution of the innermost enclosing `within`
   * block or missing that it returns the given duration.
   */
  def remainingOr(duration: FiniteDuration): FiniteDuration

  /**
   * Execute code block while bounding its execution time between `min` and
   * `max`. `within` blocks may be nested. All methods in this trait which
   * take maximum wait times are available in a version which implicitly uses
   * the remaining time governed by the innermost enclosing `within` block.
   *
   * Note that the timeout is scaled using Duration.dilated, which uses the
   * configuration entry "akka.actor.testkit.typed.timefactor", while the min Duration is not.
   *
   * {{{
   * val ret = within(50 millis) {
   *   test ! Ping
   *   expectMessageType[Pong]
   * }
   * }}}
   */
  def within[T](min: FiniteDuration, max: FiniteDuration)(f: ⇒ T): T =
    within_internal(min, max, f)
  /**
   * Same as calling `within(0 seconds, max)(f)`.
   */
  def within[T](max: FiniteDuration)(f: ⇒ T): T =
    within_internal(Duration.Zero, max, f)

  protected def within_internal[T](min: FiniteDuration, max: FiniteDuration, f: ⇒ T): T

  /**
   * Same as `expectMessage(remainingOrDefault, obj)`, but using the default timeout as deadline.
   */
  def expectMessage[T <: M](obj: T): T

  /**
   * Receive one message from the test actor and assert that it equals the
   * given object. Wait time is bounded by the given duration, with an
   * [[AssertionError]] being thrown in case of timeout.
   *
   * @return the received object
   */
  def expectMessage[T <: M](max: FiniteDuration, obj: T): T

  /**
   * Receive one message from the test actor and assert that it equals the
   * given object. Wait time is bounded by the given duration, with an
   * [[AssertionError]] being thrown in case of timeout.
   *
   * @return the received object
   */
  def expectMessage[T <: M](max: FiniteDuration, hint: String, obj: T): T

  /**
   * Assert that no message is received for the specified time.
   * Supplied value is not dilated.
   */
  def expectNoMessage(max: FiniteDuration): Unit

  /**
   * Assert that no message is received. Waits for the default period configured as `akka.actor.testkit.typed.expect-no-message-default`
   * That value is dilated.
   */
  def expectNoMessage(): Unit

  /**
   * Same as `expectMessageType[T](remainingOrDefault)`, but using the default timeout as deadline.
   */
  def expectMessageType[T <: M](implicit t: ClassTag[T]): T =
    expectMessageClass_internal(remainingOrDefault, t.runtimeClass.asInstanceOf[Class[T]])

  /**
   * Expect a message of type T to arrive within `max` or fail. `max` is dilated.
   */
  def expectMessageType[T <: M](max: FiniteDuration)(implicit t: ClassTag[T]): T =
    expectMessageClass_internal(max.dilated, t.runtimeClass.asInstanceOf[Class[T]])

  protected def expectMessageClass_internal[C](max: FiniteDuration, c: Class[C]): C

  /**
   * Receive one message of type `M` within the default timeout as deadline.
   */
  def receiveOne(): M

  /**
   * Receive one message of type `M`. Wait time is bounded by the `max` duration,
   * with an [[AssertionError]] raised in case of timeout.
   */
  def receiveOne(max: FiniteDuration): M

  /**
   * Same as `receiveN(n, remaining)` but using the default timeout as deadline.
   */
  def receiveN(n: Int): immutable.Seq[M] = receiveN_internal(n, remainingOrDefault)

  /**
   * Receive `n` messages in a row before the given deadline.
   */
  def receiveN(n: Int, max: FiniteDuration): immutable.Seq[M] = receiveN_internal(n, max.dilated)

  protected def receiveN_internal(n: Int, max: FiniteDuration): immutable.Seq[M]

  /**
   * Allows for flexible matching of multiple messages within a timeout, the fisher function is fed each incoming
   * message, and returns one of the following effects to decide on what happens next:
   *
   *  * [[FishingOutcomes.continue]] - continue with the next message given that the timeout has not been reached
   *  * [[FishingOutcomes.continueAndIgnore]] - continue and do not save the message in the returned list
   *  * [[FishingOutcomes.complete]] - successfully complete and return the message
   *  * [[FishingOutcomes.fail]] - fail the test with a custom message
   *
   * Additionally failures includes the list of messages consumed.
   * If the `fisher` function throws a match error the error
   * is decorated with some fishing details and the test is failed (making it convenient to use this method with a
   * partial function).
   *
   * @param max Max total time without the fisher function returning `CompleteFishing` before failing
   *            The timeout is dilated.
   * @return The messages accepted in the order they arrived
   */
  def fishForMessage(max: FiniteDuration, hint: String)(fisher: M ⇒ FishingOutcome): immutable.Seq[M] =
    fishForMessage_internal(max, hint, fisher)

  /**
   * Same as the other `fishForMessage` but with no hint
   */
  def fishForMessage(max: FiniteDuration)(fisher: M ⇒ FishingOutcome): immutable.Seq[M] =
    fishForMessage(max, "")(fisher)

  protected def fishForMessage_internal(max: FiniteDuration, hint: String, fisher: M ⇒ FishingOutcome): immutable.Seq[M]

  /**
   * Expect the given actor to be stopped or stop within the given timeout or
   * throw an [[AssertionError]].
   */
  def expectTerminated[U](actorRef: ActorRef[U], max: FiniteDuration): Unit

  /**
   * Evaluate the given assert every `interval` until it does not throw an exception and return the
   * result.
   *
   * If the `max` timeout expires the last exception is thrown.
   *
   * If no timeout is given, take it from the innermost enclosing `within`
   * block.
   *
   * Note that the timeout is scaled using Duration.dilated,
   * which uses the configuration entry "akka.test.timefactor".
   */
  def awaitAssert[A](a: ⇒ A, max: Duration = Duration.Undefined, interval: Duration = 100.millis): A

  /**
   * Stops the [[TestProbe.ref]], which is useful when testing watch and termination.
   */
  def stop(): Unit
}
