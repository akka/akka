/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.testkit.typed.javadsl

import java.util.function.Supplier

import akka.actor.typed.{ ActorRef, ActorSystem }
import akka.annotation.DoNotInherit
import akka.testkit.typed.internal.TestProbeImpl
import akka.testkit.typed.{ FishingOutcome, TestKitSettings }
import akka.testkit.typed.scaladsl.TestDuration

import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.collection.JavaConverters._
import scala.concurrent.duration._

object FishingOutcomes {
  /**
   * Consume this message and continue with the next
   */
  def continue(): FishingOutcome = FishingOutcome.Continue

  /**
   * Consume this message and continue with the next
   */
  def continueAndIgnore(): FishingOutcome = akka.testkit.typed.FishingOutcome.ContinueAndIgnore

  /**
   * Complete fishing and return this message
   */
  def complete(): FishingOutcome = akka.testkit.typed.FishingOutcome.Complete

  /**
   * Fail fishing with a custom error message
   */
  def fail(error: String): FishingOutcome = akka.testkit.typed.FishingOutcome.Fail(error)
}

object TestProbe {

  def create[M](system: ActorSystem[_]): TestProbe[M] =
    create(name = "testProbe", system)

  def create[M](clazz: Class[M], system: ActorSystem[_]): TestProbe[M] =
    create(system)

  def create[M](name: String, system: ActorSystem[_]): TestProbe[M] =
    new TestProbeImpl[M](name, system)

  def create[M](name: String, clazz: Class[M], system: ActorSystem[_]): TestProbe[M] =
    new TestProbeImpl[M](name, system)
}

/**
 * Java API: * Create instances through the `create` factories in the [[TestProbe]] companion.
 *
 * A test probe is essentially a queryable mailbox which can be used in place of an actor and the received
 * messages can then be asserted etc.
 *
 * Not for user extension
 */
@DoNotInherit
abstract class TestProbe[M] {

  implicit protected def settings: TestKitSettings

  /**
   * ActorRef for this TestProbe
   */
  def ref: ActorRef[M]

  /**
   * Obtain time remaining for execution of the innermost enclosing `within`
   * block or missing that it returns the properly dilated default for this
   * case from settings (key "akka.actor.typed.test.single-expect-default").
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
   * configuration entry "akka.actor.typed.test.timefactor", while the min Duration is not.
   *
   * {{{
   * val ret = within(50 millis) {
   *   test ! Ping
   *   expectMessageType[Pong]
   * }
   * }}}
   */
  def within[T](min: FiniteDuration, max: FiniteDuration)(f: Supplier[T]): T =
    within_internal(min, max, f.get())

  /**
   * Same as calling `within(0 seconds, max)(f)`.
   */
  def within[T](max: FiniteDuration)(f: Supplier[T]): T =
    within_internal(Duration.Zero, max, f.get())

  protected def within_internal[T](min: FiniteDuration, max: FiniteDuration, f: ⇒ T): T

  /**
   * Same as `expectMessage(remainingOrDefault, obj)`, but correctly treating the timeFactor.
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
   * Assert that no message is received. Waits for the default period configured as `akka.actor.typed.test.expect-no-message-default`
   * That value is dilated.
   */
  def expectNoMessage(): Unit

  /**
   * Expect the given actor to be stopped or stop withing the given timeout or
   * throw an [[AssertionError]].
   */
  def expectTerminated[U](actorRef: ActorRef[U], max: FiniteDuration): Unit

  /**
   * Evaluate the given assert every `interval` until it does not throw an exception and return the
   * result.
   *
   * If the `max` timeout expires the last exception is thrown.
   *
   * Note that the timeout is scaled using Duration.dilated, which uses the configuration entry "akka.test.timefactor".
   */
  def awaitAssert[A](max: Duration, interval: Duration, supplier: Supplier[A]): A

  /**
   * Evaluate the given assert every 100 milliseconds until it does not throw an exception and return the
   * result.
   *
   * If the `max` timeout expires the last exception is thrown.
   *
   * Note that the timeout is scaled using Duration.dilated, which uses the configuration entry "akka.test.timefactor".
   */
  def awaitAssert[A](max: Duration, supplier: Supplier[A]): A =
    awaitAssert(max, 100.millis, supplier)

  /**
   * Evaluate the given assert every 100 milliseconds until it does not throw an exception and return the
   * result. A max time is taken it from the innermost enclosing `within` block.
   */
  def awaitAssert[A](supplier: Supplier[A]): A =
    awaitAssert(Duration.Undefined, supplier)

  // FIXME awaitAssert(Procedure): Unit would be nice for java people to not have to return null

  /**
   * Same as `expectMessageType(clazz, remainingOrDefault)`, but correctly treating the timeFactor.
   */
  def expectMessageClass[T](clazz: Class[T]): T =
    expectMessageClass_internal(remainingOrDefault, clazz)

  /**
   * Wait for a message of type M and return it when it arrives, or fail if the `max` timeout is hit.
   * The timeout is dilated.
   */
  def expectMessageClass[T](clazz: Class[T], max: FiniteDuration): T =
    expectMessageClass_internal(max.dilated, clazz)

  protected def expectMessageClass_internal[C](max: FiniteDuration, c: Class[C]): C

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
  def fishForMessage(max: FiniteDuration, fisher: java.util.function.Function[M, FishingOutcome]): java.util.List[M] =
    fishForMessage(max, "", fisher)

  /**
   * Same as the other `fishForMessageJava` but includes the provided hint in all error messages
   */
  def fishForMessage(max: FiniteDuration, hint: String, fisher: java.util.function.Function[M, FishingOutcome]): java.util.List[M] =
    fishForMessage_internal(max, hint, fisher.apply).asJava

  protected def fishForMessage_internal(max: FiniteDuration, hint: String, fisher: M ⇒ FishingOutcome): List[M]

}
