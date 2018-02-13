/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.testkit.typed.scaladsl

import scala.concurrent.duration._
import java.util.concurrent.BlockingDeque

import akka.actor.typed.{ ActorRef, ActorSystem, Behavior, Terminated }
import akka.actor.typed.scaladsl.Behaviors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicInteger

import akka.annotation.DoNotInherit
import akka.util.Timeout
import akka.util.PrettyDuration.PrettyPrintableDuration

import scala.concurrent.Await
import akka.testkit.typed.{ FishingOutcome, TestKitSettings }
import akka.util.BoxedType

import scala.annotation.tailrec
import scala.reflect.ClassTag
import scala.util.control.NonFatal

object TestProbe {
  private val testActorId = new AtomicInteger(0)

  def apply[M]()(implicit system: ActorSystem[_]): TestProbe[M] =
    apply(name = "testProbe")

  def apply[M](name: String)(implicit system: ActorSystem[_]): TestProbe[M] =
    new TestProbe(name)

  private case class WatchActor[U](actor: ActorRef[U])
  private def testActor[M](queue: BlockingDeque[M], terminations: BlockingDeque[Terminated]): Behavior[M] = Behaviors.immutable[M] { (ctx, msg) ⇒
    msg match {
      case WatchActor(ref) ⇒ ctx.watch(ref)
      case other           ⇒ queue.offerLast(other)
    }
    Behaviors.same
  }.onSignal {
    case (_, t: Terminated) ⇒
      terminations.offerLast(t)
      Behaviors.same
  }
}

object FishingOutcomes {

  /**
   * Consume this message and continue with the next
   */
  case object Continue extends FishingOutcome

  /**
   * Complete fishing and return this message
   */
  case object Complete extends FishingOutcome

  /**
   * Fail fishing with a custom error message
   */
  case class Fail(error: String) extends FishingOutcome
}

class TestProbe[M](name: String)(implicit system: ActorSystem[_]) {

  import TestProbe._
  private implicit val settings = TestKitSettings(system)
  private val queue = new LinkedBlockingDeque[M]
  private val terminations = new LinkedBlockingDeque[Terminated]

  private var end: Duration = Duration.Undefined

  /**
   * if last assertion was expectNoMessage, disable timing failure upon within()
   * block end.
   */
  private var lastWasNoMessage = false

  private var lastMessage: Option[M] = None

  val testActor: ActorRef[M] = {
    implicit val timeout = Timeout(3.seconds)
    val futRef = system.systemActorOf(TestProbe.testActor(queue, terminations), s"$name-${testActorId.incrementAndGet()}")
    Await.result(futRef, timeout.duration + 1.second)
  }

  /**
   * Shorthand to get the `testActor`.
   */
  def ref: ActorRef[M] = testActor

  /**
   * Obtain current time (`System.nanoTime`) as Duration.
   */
  protected def now: FiniteDuration = System.nanoTime.nanos

  /**
   * Obtain time remaining for execution of the innermost enclosing `within`
   * block or missing that it returns the properly dilated default for this
   * case from settings (key "akka.actor.typed.test.single-expect-default").
   */
  def remainingOrDefault = remainingOr(settings.SingleExpectDefaultTimeout.dilated)

  /**
   * Obtain time remaining for execution of the innermost enclosing `within`
   * block or throw an [[AssertionError]] if no `within` block surrounds this
   * call.
   */
  def remaining: FiniteDuration = end match {
    case f: FiniteDuration ⇒ f - now
    case _                 ⇒ throw new AssertionError("`remaining` may not be called outside of `within`")
  }

  /**
   * Obtain time remaining for execution of the innermost enclosing `within`
   * block or missing that it returns the given duration.
   */
  def remainingOr(duration: FiniteDuration): FiniteDuration = end match {
    case x if x eq Duration.Undefined ⇒ duration
    case x if !x.isFinite             ⇒ throw new IllegalArgumentException("`end` cannot be infinite")
    case f: FiniteDuration            ⇒ f - now
  }

  private def remainingOrDilated(max: Duration): FiniteDuration = max match {
    case x if x eq Duration.Undefined ⇒ remainingOrDefault
    case x if !x.isFinite             ⇒ throw new IllegalArgumentException("max duration cannot be infinite")
    case f: FiniteDuration            ⇒ f.dilated
  }

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
  def within[T](min: FiniteDuration, max: FiniteDuration)(f: ⇒ T): T = {
    val _max = max.dilated
    val start = now
    val rem = if (end == Duration.Undefined) Duration.Inf else end - start
    assert(rem >= min, s"required min time $min not possible, only ${rem.pretty} left")

    lastWasNoMessage = false

    val max_diff = _max min rem
    val prev_end = end
    end = start + max_diff

    val ret = try f finally end = prev_end

    val diff = now - start
    assert(min <= diff, s"block took ${diff.pretty}, should at least have been $min")
    if (!lastWasNoMessage) {
      assert(diff <= max_diff, s"block took ${diff.pretty}, exceeding ${max_diff.pretty}")
    }

    ret
  }

  /**
   * Same as calling `within(0 seconds, max)(f)`.
   */
  def within[T](max: FiniteDuration)(f: ⇒ T): T = within(Duration.Zero, max)(f)

  /**
   * Same as `expectMessage(remainingOrDefault, obj)`, but correctly treating the timeFactor.
   */
  def expectMessage[T <: M](obj: T): T = expectMessage_internal(remainingOrDefault, obj)

  /**
   * Receive one message from the test actor and assert that it equals the
   * given object. Wait time is bounded by the given duration, with an
   * [[AssertionError]] being thrown in case of timeout.
   *
   * @return the received object
   */
  def expectMessage[T <: M](max: FiniteDuration, obj: T): T = expectMessage_internal(max.dilated, obj)

  /**
   * Receive one message from the test actor and assert that it equals the
   * given object. Wait time is bounded by the given duration, with an
   * [[AssertionError]] being thrown in case of timeout.
   *
   * @return the received object
   */
  def expectMessage[T <: M](max: FiniteDuration, hint: String, obj: T): T = expectMessage_internal(max.dilated, obj, Some(hint))

  private def expectMessage_internal[T <: M](max: Duration, obj: T, hint: Option[String] = None): T = {
    val o = receiveOne(max)
    val hintOrEmptyString = hint.map(": " + _).getOrElse("")
    assert(o != null, s"timeout ($max) during expectMessage while waiting for $obj" + hintOrEmptyString)
    assert(obj == o, s"expected $obj, found $o" + hintOrEmptyString)
    o.asInstanceOf[T]
  }

  /**
   * Receive one message from the internal queue of the TestActor. If the given
   * duration is zero, the queue is polled (non-blocking).
   *
   * This method does NOT automatically scale its Duration parameter!
   */
  private def receiveOne(max: Duration): M = {
    val message =
      if (max == Duration.Zero) {
        queue.pollFirst
      } else if (max.isFinite) {
        queue.pollFirst(max.length, max.unit)
      } else {
        queue.takeFirst
      }
    lastWasNoMessage = false
    lastMessage = if (message == null) None else Some(message)
    message
  }

  /**
   * Assert that no message is received for the specified time.
   * Supplied value is not dilated.
   */
  def expectNoMessage(max: FiniteDuration) { expectNoMessage_internal(max) }

  /**
   * Assert that no message is received. Waits for the default period configured as `akka.actor.typed.test.expect-no-message-default`
   * That value is dilated.
   */
  def expectNoMessage() { expectNoMessage_internal(settings.ExpectNoMessageDefaultTimeout.dilated) }

  private def expectNoMessage_internal(max: FiniteDuration) {
    val o = receiveOne(max)
    assert(o == null, s"received unexpected message $o")
    lastWasNoMessage = true
  }

  /**
   * Same as `expectMessageType[T](remainingOrDefault)`, but correctly treating the timeFactor.
   */
  def expectMessageType[T <: M](implicit t: ClassTag[T]): T =
    expectMessageClass_internal(remainingOrDefault, t.runtimeClass.asInstanceOf[Class[T]])

  /**
   * Receive one message from the test actor and assert that it conforms to the
   * given type (after erasure). Wait time is bounded by the given duration,
   * with an [[AssertionError]] being thrown in case of timeout.
   *
   * @return the received object
   */
  def expectMessageType[T <: M](max: FiniteDuration)(implicit t: ClassTag[T]): T =
    expectMessageClass_internal(max.dilated, t.runtimeClass.asInstanceOf[Class[T]])

  private[akka] def expectMessageClass_internal[C](max: FiniteDuration, c: Class[C]): C = {
    val o = receiveOne(max)
    assert(o != null, s"timeout ($max) during expectMessageClass waiting for $c")
    assert(BoxedType(c) isInstance o, s"expected $c, found ${o.getClass} ($o)")
    o.asInstanceOf[C]
  }

  /**
   * Allows for flexible matching of multiple messages within a timeout, the fisher function is fed each incoming
   * message, and returns one of the following effects to decide on what happens next:
   *
   *  * [[FishingOutcomes.Continue]] - continue with the next message given that the timeout has not been reached
   *  * [[FishingOutcomes.Complete]] - successfully complete and return the message
   *  * [[FishingOutcomes.Fail]] - fail the test with a custom message
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
  def fishForMessage(max: FiniteDuration)(fisher: M ⇒ FishingOutcome): List[M] = {
    // not tailrec but that should be ok
    def loop(timeout: FiniteDuration, seen: List[M]): List[M] = {
      val start = System.nanoTime()
      val msg = receiveOne(timeout)
      try {
        fisher(msg) match {
          case FishingOutcomes.Complete    ⇒ (msg :: seen).reverse
          case FishingOutcomes.Fail(error) ⇒ throw new AssertionError(error)
          case FishingOutcomes.Continue ⇒
            val newTimeout =
              if (timeout.isFinite()) timeout - (System.nanoTime() - start).nanos
              else timeout
            if (newTimeout.toMillis <= 0) {
              throw new AssertionError(s"timeout ($max) during fishForMessage, seen messages ${seen.reverse}")
            } else {
              loop(max, msg :: seen)
            }
        }
      } catch {
        case ex: MatchError ⇒ throw new AssertionError(
          s"Unexpected message $msg while fishing for messages, " +
            s"seen messages ${seen.reverse}", ex)
      }
    }

    loop(max.dilated, Nil)
  }

  /**
   * Expect the given actor to be stopped or stop withing the given timeout or
   * throw an [[AssertionError]].
   */
  def expectTerminated[U](actorRef: ActorRef[U], max: FiniteDuration): Unit = {
    testActor.asInstanceOf[ActorRef[AnyRef]] ! WatchActor(actorRef)
    val message =
      if (max == Duration.Zero) {
        terminations.pollFirst
      } else if (max.isFinite) {
        terminations.pollFirst(max.length, max.unit)
      } else {
        terminations.takeFirst
      }
    assert(message != null, s"timeout ($max) during expectStop waiting for actor [${actorRef.path}] to stop")
    assert(message.ref == actorRef, s"expected [${actorRef.path}] to stop, but saw [${message.ref.path}] stop")
  }

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
  def awaitAssert[A](a: ⇒ A, max: Duration = Duration.Undefined, interval: Duration = 100.millis): A = {
    val _max = remainingOrDilated(max)
    val stop = now + _max

    @tailrec
    def poll(t: Duration): A = {
      val result: A =
        try {
          a
        } catch {
          case NonFatal(e) ⇒
            if ((now + t) >= stop) throw e
            else null.asInstanceOf[A]
        }

      if (result != null) result
      else {
        Thread.sleep(t.toMillis)
        poll((stop - now) min interval)
      }
    }

    poll(_max min interval)
  }

}
