/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.testkit.scaladsl

import scala.concurrent.duration._
import java.util.concurrent.BlockingDeque
import akka.typed.Behavior
import akka.typed.scaladsl.Actor
import akka.typed.ActorSystem
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicInteger
import akka.typed.ActorRef
import akka.util.Timeout
import akka.util.PrettyDuration.PrettyPrintableDuration
import scala.concurrent.Await
import com.typesafe.config.Config
import akka.typed.testkit.TestKitSettings
import akka.util.BoxedType
import scala.reflect.ClassTag

object TestProbe {
  private val testActorId = new AtomicInteger(0)

  def apply[M]()(implicit system: ActorSystem[_], settings: TestKitSettings): TestProbe[M] =
    apply(name = "testProbe")

  def apply[M](name: String)(implicit system: ActorSystem[_], settings: TestKitSettings): TestProbe[M] =
    new TestProbe(name)

  private def testActor[M](queue: BlockingDeque[M]): Behavior[M] = Actor.immutable { (ctx, msg) ⇒
    queue.offerLast(msg)
    Actor.same
  }
}

class TestProbe[M](name: String)(implicit val system: ActorSystem[_], val settings: TestKitSettings) {
  import TestProbe._
  private val queue = new LinkedBlockingDeque[M]

  private var end: Duration = Duration.Undefined

  /**
   * if last assertion was expectNoMsg, disable timing failure upon within()
   * block end.
   */
  private var lastWasNoMsg = false

  private var lastMessage: Option[M] = None

  val testActor: ActorRef[M] = {
    implicit val timeout = Timeout(3.seconds)
    val futRef = system.systemActorOf(TestProbe.testActor(queue), s"$name-${testActorId.incrementAndGet()}")
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
   * case from settings (key "akka.typed.test.single-expect-default").
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
   * configuration entry "akka.typed.test.timefactor", while the min Duration is not.
   *
   * {{{
   * val ret = within(50 millis) {
   *   test ! Ping
   *   expectMsgType[Pong]
   * }
   * }}}
   */
  def within[T](min: FiniteDuration, max: FiniteDuration)(f: ⇒ T): T = {
    val _max = max.dilated
    val start = now
    val rem = if (end == Duration.Undefined) Duration.Inf else end - start
    assert(rem >= min, s"required min time $min not possible, only ${rem.pretty} left")

    lastWasNoMsg = false

    val max_diff = _max min rem
    val prev_end = end
    end = start + max_diff

    val ret = try f finally end = prev_end

    val diff = now - start
    assert(min <= diff, s"block took ${diff.pretty}, should at least have been $min")
    if (!lastWasNoMsg) {
      assert(diff <= max_diff, s"block took ${diff.pretty}, exceeding ${max_diff.pretty}")
    }

    ret
  }

  /**
   * Same as calling `within(0 seconds, max)(f)`.
   */
  def within[T](max: FiniteDuration)(f: ⇒ T): T = within(Duration.Zero, max)(f)

  /**
   * Same as `expectMsg(remainingOrDefault, obj)`, but correctly treating the timeFactor.
   */
  def expectMsg[T <: M](obj: T): T = expectMsg_internal(remainingOrDefault, obj)

  /**
   * Receive one message from the test actor and assert that it equals the
   * given object. Wait time is bounded by the given duration, with an
   * AssertionFailure being thrown in case of timeout.
   *
   * @return the received object
   */
  def expectMsg[T <: M](max: FiniteDuration, obj: T): T = expectMsg_internal(max.dilated, obj)

  /**
   * Receive one message from the test actor and assert that it equals the
   * given object. Wait time is bounded by the given duration, with an
   * AssertionFailure being thrown in case of timeout.
   *
   * @return the received object
   */
  def expectMsg[T <: M](max: FiniteDuration, hint: String, obj: T): T = expectMsg_internal(max.dilated, obj, Some(hint))

  private def expectMsg_internal[T <: M](max: Duration, obj: T, hint: Option[String] = None): T = {
    val o = receiveOne(max)
    val hintOrEmptyString = hint.map(": " + _).getOrElse("")
    assert(o != null, s"timeout ($max) during expectMsg while waiting for $obj" + hintOrEmptyString)
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
      if (max == 0.seconds) {
        queue.pollFirst
      } else if (max.isFinite) {
        queue.pollFirst(max.length, max.unit)
      } else {
        queue.takeFirst
      }
    lastWasNoMsg = false
    lastMessage = if (message == null) None else Some(message)
    message
  }

  /**
   * Assert that no message is received for the specified time.
   */
  def expectNoMsg(max: FiniteDuration) { expectNoMsg_internal(max.dilated) }

  private def expectNoMsg_internal(max: FiniteDuration) {
    val o = receiveOne(max)
    assert(o == null, s"received unexpected message $o")
    lastWasNoMsg = true
  }

  /**
   * Same as `expectMsgType[T](remainingOrDefault)`, but correctly treating the timeFactor.
   */
  def expectMsgType[T <: M](implicit t: ClassTag[T]): T =
    expectMsgClass_internal(remainingOrDefault, t.runtimeClass.asInstanceOf[Class[T]])

  /**
   * Receive one message from the test actor and assert that it conforms to the
   * given type (after erasure). Wait time is bounded by the given duration,
   * with an AssertionFailure being thrown in case of timeout.
   *
   * @return the received object
   */
  def expectMsgType[T <: M](max: FiniteDuration)(implicit t: ClassTag[T]): T =
    expectMsgClass_internal(max.dilated, t.runtimeClass.asInstanceOf[Class[T]])

  private def expectMsgClass_internal[C](max: FiniteDuration, c: Class[C]): C = {
    val o = receiveOne(max)
    assert(o != null, s"timeout ($max) during expectMsgClass waiting for $c")
    assert(BoxedType(c) isInstance o, s"expected $c, found ${o.getClass} ($o)")
    o.asInstanceOf[C]
  }

}
