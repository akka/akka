/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed.internal

import java.time.{ Duration => JDuration }
import java.util.concurrent.BlockingDeque
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier
import java.util.{ List => JList }

import scala.annotation.tailrec
import akka.util.ccompat.JavaConverters._
import scala.collection.immutable
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.control.NonFatal

import akka.actor.testkit.typed.FishingOutcome
import akka.actor.testkit.typed.TestKitSettings
import akka.actor.testkit.typed.javadsl.{ TestProbe => JavaTestProbe }
import akka.actor.testkit.typed.scaladsl.TestDuration
import akka.actor.testkit.typed.scaladsl.{ TestProbe => ScalaTestProbe }
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.Terminated
import akka.actor.typed.scaladsl.Behaviors
import akka.annotation.InternalApi
import akka.util.BoxedType
import akka.util.JavaDurationConverters._
import akka.util.PrettyDuration._
import akka.util.Timeout

@InternalApi
private[akka] object TestProbeImpl {
  private val testActorId = new AtomicInteger(0)

  private final case class WatchActor[U](actor: ActorRef[U])
  private case object Stop

  private def testActor[M](queue: BlockingDeque[M], terminations: BlockingDeque[Terminated]): Behavior[M] =
    Behaviors
      .receive[M] { (context, msg) =>
        msg match {
          case WatchActor(ref) =>
            context.watch(ref)
            Behaviors.same
          case Stop =>
            Behaviors.stopped
          case other =>
            queue.offerLast(other)
            Behaviors.same
        }
      }
      .receiveSignal {
        case (_, t: Terminated) =>
          terminations.offerLast(t)
          Behaviors.same
      }
}

@InternalApi
private[akka] final class TestProbeImpl[M](name: String, system: ActorSystem[_])
    extends JavaTestProbe[M]
    with ScalaTestProbe[M] {

  import TestProbeImpl._
  protected implicit val settings: TestKitSettings = TestKitSettings(system)
  private val queue = new LinkedBlockingDeque[M]
  private val terminations = new LinkedBlockingDeque[Terminated]

  private var end: Duration = Duration.Undefined

  /**
   * if last assertion was expectNoMessage, disable timing failure upon within()
   * block end.
   */
  private var lastWasNoMessage = false

  private val testActor: ActorRef[M] = {
    // FIXME arbitrary timeout?
    implicit val timeout: Timeout = Timeout(3.seconds)
    val futRef =
      system.systemActorOf(TestProbeImpl.testActor(queue, terminations), s"$name-${testActorId.incrementAndGet()}")
    Await.result(futRef, timeout.duration + 1.second)
  }

  override def ref: ActorRef[M] = testActor

  override def remainingOrDefault: FiniteDuration = remainingOr(settings.SingleExpectDefaultTimeout.dilated)

  override def getRemainingOrDefault: JDuration = remainingOrDefault.asJava

  override def remaining: FiniteDuration = end match {
    case f: FiniteDuration => f - now
    case _                 => assertFail("`remaining` may not be called outside of `within`")
  }

  override def getRemaining: JDuration = remaining.asJava

  override def remainingOr(duration: FiniteDuration): FiniteDuration = end match {
    case x if x eq Duration.Undefined => duration
    case x if !x.isFinite             => throw new IllegalArgumentException("`end` cannot be infinite")
    case f: FiniteDuration            => f - now
  }

  override def getRemainingOr(duration: JDuration): JDuration =
    remainingOr(duration.asScala).asJava

  override def within[T](min: FiniteDuration, max: FiniteDuration)(f: => T): T =
    within_internal(min, max.dilated, f)

  override def within[T](max: FiniteDuration)(f: => T): T =
    within_internal(Duration.Zero, max.dilated, f)

  override def within[T](min: JDuration, max: JDuration)(f: Supplier[T]): T =
    within_internal(min.asScala, max.asScala.dilated, f.get())

  def within[T](max: JDuration)(f: Supplier[T]): T =
    within_internal(Duration.Zero, max.asScala.dilated, f.get())

  private def within_internal[T](min: FiniteDuration, max: FiniteDuration, f: => T): T = {
    val start = now
    val rem = if (end == Duration.Undefined) Duration.Inf else end - start
    assert(rem >= min, s"required min time $min not possible, only ${rem.pretty} left")

    lastWasNoMessage = false

    val maxDiff = max min rem
    val prevEnd = end
    end = start + maxDiff

    val ret = try f
    finally end = prevEnd

    val diff = now - start
    assert(min <= diff, s"block took ${diff.pretty}, should at least have been $min")
    if (!lastWasNoMessage) {
      assert(diff <= maxDiff, s"block took ${diff.pretty}, exceeding ${maxDiff.pretty}")
    }

    ret
  }

  override def expectMessage[T <: M](obj: T): T = expectMessage_internal(remainingOrDefault, obj)

  override def expectMessage[T <: M](max: FiniteDuration, obj: T): T = expectMessage_internal(max.dilated, obj)

  override def expectMessage[T <: M](max: JDuration, obj: T): T =
    expectMessage(max.asScala, obj)

  override def expectMessage[T <: M](max: FiniteDuration, hint: String, obj: T): T =
    expectMessage_internal(max.dilated, obj, Some(hint))

  override def expectMessage[T <: M](max: JDuration, hint: String, obj: T): T =
    expectMessage(max.asScala, hint, obj)

  private def expectMessage_internal[T <: M](max: FiniteDuration, obj: T, hint: Option[String] = None): T = {
    val o = receiveOne_internal(max)
    val hintOrEmptyString = hint.map(": " + _).getOrElse("")
    o match {
      case Some(m) if obj == m => m.asInstanceOf[T]
      case Some(m)             => assertFail(s"expected $obj, found $m$hintOrEmptyString")
      case None                => assertFail(s"timeout ($max) during expectMessage while waiting for $obj$hintOrEmptyString")
    }
  }

  override def receiveMessage(): M = receiveMessage_internal(remainingOrDefault)

  override def receiveMessage(max: JDuration): M = receiveMessage(max.asScala)

  override def receiveMessage(max: FiniteDuration): M = receiveMessage_internal(max.dilated)

  def receiveMessage_internal(max: FiniteDuration): M =
    receiveOne_internal(max).getOrElse(assertFail(s"Timeout ($max) during receiveMessage while waiting for message."))

  /**
   * Receive one message from the internal queue of the TestActor. If the given
   * duration is zero, the queue is polled (non-blocking).
   *
   * This method does NOT automatically scale its Duration parameter!
   */
  private def receiveOne_internal(max: FiniteDuration): Option[M] = {
    val message = Option(if (max == Duration.Zero) {
      queue.pollFirst
    } else {
      queue.pollFirst(max.length, max.unit)
    })
    lastWasNoMessage = false
    message
  }

  override def expectNoMessage(max: FiniteDuration): Unit =
    expectNoMessage_internal(max)

  override def expectNoMessage(max: JDuration): Unit =
    expectNoMessage(max.asScala)

  override def expectNoMessage(): Unit =
    expectNoMessage_internal(settings.ExpectNoMessageDefaultTimeout.dilated)

  private def expectNoMessage_internal(max: FiniteDuration): Unit = {
    val o = receiveOne_internal(max)
    o match {
      case None    => lastWasNoMessage = true
      case Some(m) => assertFail(s"Received unexpected message $m")
    }
  }

  override def expectMessageType[T <: M](implicit t: ClassTag[T]): T =
    expectMessageClass_internal(remainingOrDefault, t.runtimeClass.asInstanceOf[Class[T]])

  override def expectMessageType[T <: M](max: FiniteDuration)(implicit t: ClassTag[T]): T =
    expectMessageClass_internal(max.dilated, t.runtimeClass.asInstanceOf[Class[T]])

  override def expectMessageClass[T <: M](clazz: Class[T]): T =
    expectMessageClass_internal(getRemainingOrDefault.asScala, clazz)

  override def expectMessageClass[T <: M](clazz: Class[T], max: JDuration): T =
    expectMessageClass_internal(max.asScala.dilated, clazz)

  private def expectMessageClass_internal[C](max: FiniteDuration, c: Class[C]): C = {
    val o = receiveOne_internal(max)
    val bt = BoxedType(c)
    o match {
      case Some(m) if bt.isInstance(m) => m.asInstanceOf[C]
      case Some(m)                     => assertFail(s"Expected $c, found ${m.getClass} ($m)")
      case None                        => assertFail(s"Timeout ($max) during expectMessageClass waiting for $c")
    }
  }

  override def receiveMessages(n: Int): immutable.Seq[M] =
    receiveMessages_internal(n, remainingOrDefault)

  override def receiveMessages(n: Int, max: FiniteDuration): immutable.Seq[M] =
    receiveMessages_internal(n, max.dilated)

  override def receiveSeveralMessages(n: Int): JList[M] =
    receiveMessages_internal(n, getRemainingOrDefault.asScala).asJava

  override def receiveSeveralMessages(n: Int, max: JDuration): JList[M] =
    receiveMessages_internal(n, max.asScala.dilated).asJava

  private def receiveMessages_internal(n: Int, max: FiniteDuration): immutable.Seq[M] = {
    val stop = max + now
    for (x <- 1 to n) yield {
      val timeout = stop - now
      val o = receiveOne_internal(timeout)
      o match {
        case Some(m) => m
        case None    => assertFail(s"timeout ($max) while expecting $n messages (got ${x - 1})")
      }
    }
  }

  override def fishForMessage(max: FiniteDuration, hint: String)(fisher: M => FishingOutcome): immutable.Seq[M] =
    fishForMessage_internal(max.dilated, hint, fisher)

  override def fishForMessage(max: FiniteDuration)(fisher: M => FishingOutcome): immutable.Seq[M] =
    fishForMessage(max, "")(fisher)

  override def fishForMessage(max: JDuration, fisher: java.util.function.Function[M, FishingOutcome]): JList[M] =
    fishForMessage(max, "", fisher)

  override def fishForMessage(
      max: JDuration,
      hint: String,
      fisher: java.util.function.Function[M, FishingOutcome]): JList[M] =
    fishForMessage_internal(max.asScala.dilated, hint, fisher.apply).asJava

  private def fishForMessage_internal(max: FiniteDuration, hint: String, fisher: M => FishingOutcome): List[M] = {
    @tailrec def loop(timeout: FiniteDuration, seen: List[M]): List[M] = {
      val start = System.nanoTime()
      val maybeMsg = receiveOne_internal(timeout)
      maybeMsg match {
        case Some(message) =>
          val outcome =
            try fisher(message)
            catch {
              case ex: MatchError =>
                throw new AssertionError(
                  s"Unexpected message $message while fishing for messages, " +
                  s"seen messages ${seen.reverse}, hint: $hint",
                  ex)
            }
          outcome match {
            case FishingOutcome.Complete    => (message :: seen).reverse
            case FishingOutcome.Fail(error) => assertFail(s"$error, hint: $hint")
            case continue: FishingOutcome.ContinueOutcome =>
              val newTimeout = timeout - (System.nanoTime() - start).nanos
              continue match {
                case FishingOutcome.Continue          => loop(newTimeout, message :: seen)
                case FishingOutcome.ContinueAndIgnore => loop(newTimeout, seen)
              }
          }

        case None =>
          assertFail(s"timeout ($max) during fishForMessage, seen messages ${seen.reverse}, hint: $hint")
      }
    }

    loop(max, Nil)
  }

  override def expectTerminated[U](actorRef: ActorRef[U], max: FiniteDuration): Unit =
    expectTerminated_internal(actorRef, max.dilated)

  override def expectTerminated[U](actorRef: ActorRef[U]): Unit =
    expectTerminated_internal(actorRef, remainingOrDefault)

  override def expectTerminated[U](actorRef: ActorRef[U], max: JDuration): Unit =
    expectTerminated_internal(actorRef, max.asScala.dilated)

  private def expectTerminated_internal[U](actorRef: ActorRef[U], max: FiniteDuration): Unit = {
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

  override def awaitAssert[A](a: => A, max: FiniteDuration, interval: FiniteDuration): A =
    awaitAssert_internal(a, max.dilated, interval)

  override def awaitAssert[A](a: => A, max: FiniteDuration): A =
    awaitAssert_internal(a, max.dilated, 100.millis)

  override def awaitAssert[A](a: => A): A =
    awaitAssert_internal(a, remainingOrDefault, 100.millis)

  override def awaitAssert[A](max: JDuration, interval: JDuration, supplier: Supplier[A]): A =
    awaitAssert_internal(supplier.get(), max.asScala.dilated, interval.asScala)

  def awaitAssert[A](max: JDuration, supplier: Supplier[A]): A =
    awaitAssert(max, JDuration.ofMillis(100), supplier)

  def awaitAssert[A](supplier: Supplier[A]): A =
    awaitAssert(getRemainingOrDefault, supplier)

  private def awaitAssert_internal[A](a: => A, max: FiniteDuration, interval: FiniteDuration): A = {
    val stop = now + max

    @tailrec
    def poll(t: Duration): A = {
      // cannot use null-ness of result as signal it failed
      // because Java API and not wanting to return a value will be "return null"
      var failed = false
      val result: A =
        try {
          val aRes = a
          failed = false
          aRes
        } catch {
          case NonFatal(e) =>
            failed = true
            if ((now + t) >= stop) throw e
            else null.asInstanceOf[A]
        }

      if (!failed) result
      else {
        Thread.sleep(t.toMillis)
        poll((stop - now) min interval)
      }
    }

    poll(max min interval)
  }

  /**
   * Obtain current time (`System.nanoTime`) as Duration.
   */
  private def now: FiniteDuration = System.nanoTime.nanos

  private def assertFail(msg: String): Nothing = throw new AssertionError(msg)

  override def stop(): Unit = {
    testActor.asInstanceOf[ActorRef[AnyRef]] ! Stop
  }

}
