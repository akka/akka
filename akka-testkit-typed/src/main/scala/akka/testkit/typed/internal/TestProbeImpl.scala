/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.testkit.typed.internal

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ BlockingDeque, LinkedBlockingDeque }
import java.util.function.Supplier

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior, Terminated }
import akka.annotation.InternalApi
import akka.testkit.typed.javadsl.{ TestProbe ⇒ JavaTestProbe }
import akka.testkit.typed.scaladsl.{ TestDuration, TestProbe ⇒ ScalaTestProbe }
import akka.testkit.typed.{ FishingOutcome, TestKitSettings }
import akka.util.PrettyDuration._
import akka.util.{ BoxedType, Timeout }

import scala.annotation.tailrec
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.control.NonFatal

@InternalApi
private[akka] object TestProbeImpl {
  private val testActorId = new AtomicInteger(0)

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

@InternalApi
private[akka] final class TestProbeImpl[M](name: String, system: ActorSystem[_]) extends JavaTestProbe[M] with ScalaTestProbe[M] {

  import TestProbeImpl._
  protected implicit val settings = TestKitSettings(system)
  private val queue = new LinkedBlockingDeque[M]
  private val terminations = new LinkedBlockingDeque[Terminated]

  private var end: Duration = Duration.Undefined

  /**
   * if last assertion was expectNoMessage, disable timing failure upon within()
   * block end.
   */
  private var lastWasNoMessage = false

  private var lastMessage: Option[M] = None

  private val testActor: ActorRef[M] = {
    // FIXME arbitrary timeout?
    implicit val timeout = Timeout(3.seconds)
    val futRef = system.systemActorOf(TestProbeImpl.testActor(queue, terminations), s"$name-${testActorId.incrementAndGet()}")
    Await.result(futRef, timeout.duration + 1.second)
  }

  override def ref = testActor

  override def remainingOrDefault = remainingOr(settings.SingleExpectDefaultTimeout.dilated)

  override def remaining: FiniteDuration = end match {
    case f: FiniteDuration ⇒ f - now
    case _                 ⇒ throw new AssertionError("`remaining` may not be called outside of `within`")
  }

  override def remainingOr(duration: FiniteDuration): FiniteDuration = end match {
    case x if x eq Duration.Undefined ⇒ duration
    case x if !x.isFinite             ⇒ throw new IllegalArgumentException("`end` cannot be infinite")
    case f: FiniteDuration            ⇒ f - now
  }

  private def remainingOrDilated(max: Duration): FiniteDuration = max match {
    case x if x eq Duration.Undefined ⇒ remainingOrDefault
    case x if !x.isFinite             ⇒ throw new IllegalArgumentException("max duration cannot be infinite")
    case f: FiniteDuration            ⇒ f.dilated
  }

  override protected def within_internal[T](min: FiniteDuration, max: FiniteDuration, f: ⇒ T): T = {
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

  override def expectMessage[T <: M](obj: T): T = expectMessage_internal(remainingOrDefault, obj)

  override def expectMessage[T <: M](max: FiniteDuration, obj: T): T = expectMessage_internal(max.dilated, obj)

  override def expectMessage[T <: M](max: FiniteDuration, hint: String, obj: T): T =
    expectMessage_internal(max.dilated, obj, Some(hint))

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

  override def expectNoMessage(max: FiniteDuration): Unit = { expectNoMessage_internal(max) }

  override def expectNoMessage(): Unit = { expectNoMessage_internal(settings.ExpectNoMessageDefaultTimeout.dilated) }

  private def expectNoMessage_internal(max: FiniteDuration) {
    val o = receiveOne(max)
    assert(o == null, s"received unexpected message $o")
    lastWasNoMessage = true
  }

  override protected def expectMessageClass_internal[C](max: FiniteDuration, c: Class[C]): C = {
    val o = receiveOne(max)
    assert(o != null, s"timeout ($max) during expectMessageClass waiting for $c")
    assert(BoxedType(c) isInstance o, s"expected $c, found ${o.getClass} ($o)")
    o.asInstanceOf[C]
  }

  override protected def fishForMessage_internal(max: FiniteDuration, hint: String, fisher: M ⇒ FishingOutcome): List[M] = {
    // not tailrec but that should be ok
    def loop(timeout: FiniteDuration, seen: List[M]): List[M] = {
      val start = System.nanoTime()
      val msg = receiveOne(timeout)
      try {
        fisher(msg) match {
          case FishingOutcome.Complete    ⇒ (msg :: seen).reverse
          case FishingOutcome.Fail(error) ⇒ throw new AssertionError(s"$error, hint: $hint")
          case continue ⇒
            val newTimeout =
              if (timeout.isFinite()) timeout - (System.nanoTime() - start).nanos
              else timeout
            if (newTimeout.toMillis <= 0) {
              throw new AssertionError(s"timeout ($max) during fishForMessage, seen messages ${seen.reverse}, hint: $hint")
            } else {

              continue match {
                case FishingOutcome.Continue          ⇒ loop(newTimeout, msg :: seen)
                case FishingOutcome.ContinueAndIgnore ⇒ loop(newTimeout, seen)
                case _                                ⇒ ??? // cannot happen
              }

            }
        }
      } catch {
        case ex: MatchError ⇒ throw new AssertionError(
          s"Unexpected message $msg while fishing for messages, " +
            s"seen messages ${seen.reverse}, hint: $hint", ex)
      }
    }

    loop(max.dilated, Nil)
  }

  override def expectTerminated[U](actorRef: ActorRef[U], max: FiniteDuration): Unit = {
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

  override def awaitAssert[A](max: Duration, interval: Duration, supplier: Supplier[A]): A =
    awaitAssert(supplier.get(), max, interval)

  override def awaitAssert[A](a: ⇒ A, max: Duration = Duration.Undefined, interval: Duration = 100.millis): A = {
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

  /**
   * Obtain current time (`System.nanoTime`) as Duration.
   */
  private def now: FiniteDuration = System.nanoTime.nanos

}
