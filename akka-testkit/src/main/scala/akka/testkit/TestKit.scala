/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.testkit

import akka.actor._
import Actor._
import akka.util.Duration
import akka.util.duration._

import java.util.concurrent.{ BlockingDeque, LinkedBlockingDeque, TimeUnit }

import scala.annotation.tailrec

object TestActor {
  type Ignore = Option[PartialFunction[AnyRef, Boolean]]

  case class SetTimeout(d: Duration)
  case class SetIgnore(i: Ignore)

  trait Message {
    def msg: AnyRef
    def channel: UntypedChannel
  }
  case class RealMessage(msg: AnyRef, channel: UntypedChannel) extends Message
  case object NullMessage extends Message {
    override def msg: AnyRef = throw new IllegalActorStateException("last receive did not dequeue a message")
    override def channel: UntypedChannel = throw new IllegalActorStateException("last receive did not dequeue a message")
  }
}

class TestActor(queue: BlockingDeque[TestActor.Message]) extends Actor with FSM[Int, TestActor.Ignore] {
  import FSM._
  import TestActor._

  self.dispatcher = CallingThreadDispatcher.global

  startWith(0, None)
  when(0, stateTimeout = 5 seconds) {
    case Ev(SetTimeout(d)) ⇒
      setStateTimeout(0, if (d.finite_?) d else None)
      stay
    case Ev(SetIgnore(ign)) ⇒ stay using ign
    case Ev(StateTimeout) ⇒
      stop
    case Event(x: AnyRef, data) ⇒
      val observe = data map (ignoreFunc ⇒ if (ignoreFunc isDefinedAt x) !ignoreFunc(x) else true) getOrElse true
      if (observe)
        queue.offerLast(RealMessage(x, self.channel))
      stay
  }
  initialize
}

/**
 * Test kit for testing actors. Inheriting from this trait enables reception of
 * replies from actors, which are queued by an internal actor and can be
 * examined using the `expectMsg...` methods. Assertions and bounds concerning
 * timing are available in the form of `within` blocks.
 *
 * <pre>
 * class Test extends TestKit {
 *     val test = actorOf[SomeActor].start()
 *
 *     within (1 second) {
 *       test ! SomeWork
 *       expectMsg(Result1) // bounded to 1 second
 *       expectMsg(Result2) // bounded to the remainder of the 1 second
 *     }
 * }
 * </pre>
 *
 * Beware of two points:
 *
 *  - the internal test actor needs to be stopped, either explicitly using
 *    `stopTestActor` or implicitly by using its internal inactivity timeout,
 *    see `setTestActorTimeout`
 *  - this trait is not thread-safe (only one actor with one queue, one stack
 *    of `within` blocks); it is expected that the code is executed from a
 *    constructor as shown above, which makes this a non-issue, otherwise take
 *    care not to run tests within a single test class instance in parallel.
 *
 * It should be noted that for CI servers and the like all maximum Durations
 * are scaled using their Duration.dilated method, which uses the
 * Duration.timeFactor settable via akka.conf entry "akka.test.timefactor".
 *
 * @author Roland Kuhn
 * @since 1.1
 */
trait TestKitLight {

  import TestActor.{ Message, RealMessage, NullMessage }

  private val queue = new LinkedBlockingDeque[Message]()
  private[akka] var lastMessage: Message = NullMessage

  /**
   * ActorRef of the test actor. Access is provided to enable e.g.
   * registration as message target.
   */
  val testActor = actorOf(new TestActor(queue)).start()

  /**
   * Implicit sender reference so that replies are possible for messages sent
   * from the test class.
   */
  @deprecated("will be removed after 1.2, replaced by implicit testActor", "1.2")
  val senderOption = Some(testActor)

  private var _end: Duration = Duration.Inf

  /**
   * if last assertion was expectNoMsg, disable timing failure upon within()
   * block end.
   */
  private var lastWasNoMsg = false

  /**
   * Stop test actor. Should be done at the end of the test unless relying on
   * test actor timeout.
   */
  def stopTestActor() { testActor.stop() }

  /**
   * Set test actor timeout. By default, the test actor shuts itself down
   * after 5 seconds of inactivity. Set this to Duration.Inf to disable this
   * behavior, but make sure that someone will then call `stopTestActor`,
   * unless you want to leak actors, e.g. wrap test in
   *
   * <pre>
   *   try {
   *     ...
   *   } finally { stopTestActor }
   * </pre>
   */
  def setTestActorTimeout(d: Duration) { testActor ! TestActor.SetTimeout(d) }

  /**
   * Ignore all messages in the test actor for which the given partial
   * function returns true.
   */
  def ignoreMsg(f: PartialFunction[AnyRef, Boolean]) { testActor ! TestActor.SetIgnore(Some(f)) }

  /**
   * Stop ignoring messages in the test actor.
   */
  def ignoreNoMsg() { testActor ! TestActor.SetIgnore(None) }

  /**
   * Obtain current time (`System.nanoTime`) as Duration.
   */
  def now: Duration = System.nanoTime.nanos

  /**
   * Obtain time remaining for execution of the innermost enclosing `within` block.
   */
  def remaining: Duration = _end - now

  /**
   * Block until the given condition evaluates to `true` or the timeout
   * expires, whichever comes first.
   *
   * If no timeout is given, take it from the innermost enclosing `within`
   * block.
   *
   * Note that the timeout is scaled using Duration.timeFactor.
   */
  def awaitCond(p: ⇒ Boolean, max: Duration = Duration.MinusInf, interval: Duration = 100.millis) {
    val _max = if (max eq Duration.MinusInf) remaining else max.dilated
    val stop = now + _max

    @tailrec
    def poll(t: Duration) {
      if (!p) {
        assert(now < stop, "timeout " + _max + " expired")
        Thread.sleep(t.toMillis)
        poll((stop - now) min interval)
      }
    }

    poll(_max min interval)
  }

  /**
   * Execute code block while bounding its execution time between `min` and
   * `max`. `within` blocks may be nested. All methods in this trait which
   * take maximum wait times are available in a version which implicitly uses
   * the remaining time governed by the innermost enclosing `within` block.
   *
   * Note that the max Duration is scaled by Duration.timeFactor while the min
   * Duration is not.
   *
   * <pre>
   * val ret = within(50 millis) {
   *         test ! "ping"
   *         expectMsgClass(classOf[String])
   *       }
   * </pre>
   */
  def within[T](min: Duration, max: Duration)(f: ⇒ T): T = {
    val _max = max.dilated
    val start = now
    val rem = _end - start
    assert(rem >= min, "required min time " + min + " not possible, only " + format(min.unit, rem) + " left")

    lastWasNoMsg = false

    val max_diff = _max min rem
    val prev_end = _end
    _end = start + max_diff

    val ret = try f finally _end = prev_end

    val diff = now - start
    assert(min <= diff, "block took " + format(min.unit, diff) + ", should at least have been " + min)
    if (!lastWasNoMsg) {
      assert(diff <= max_diff, "block took " + format(_max.unit, diff) + ", exceeding " + format(_max.unit, max_diff))
    }

    ret
  }

  /**
   * Same as calling `within(0 seconds, max)(f)`.
   */
  def within[T](max: Duration)(f: ⇒ T): T = within(0 seconds, max)(f)

  /**
   * Send reply to the last dequeued message. Will throw
   * IllegalActorStateException if no message has been dequeued, yet. Dequeuing
   * means reception of the message as part of an expect... or receive... call,
   * not reception by the testActor.
   */
  def reply(msg: AnyRef) { lastMessage.channel.!(msg)(testActor) }

  /**
   * Same as `expectMsg(remaining, obj)`, but correctly treating the timeFactor.
   */
  def expectMsg[T](obj: T): T = expectMsg_internal(remaining, obj)

  /**
   * Receive one message from the test actor and assert that it equals the
   * given object. Wait time is bounded by the given duration, with an
   * AssertionFailure being thrown in case of timeout.
   *
   * @return the received object
   */
  def expectMsg[T](max: Duration, obj: T): T = expectMsg_internal(max.dilated, obj)

  private def expectMsg_internal[T](max: Duration, obj: T): T = {
    val o = receiveOne(max)
    assert(o ne null, "timeout during expectMsg while waiting for " + obj)
    assert(obj == o, "expected " + obj + ", found " + o)
    o.asInstanceOf[T]
  }

  /**
   * Receive one message from the test actor and assert that the given
   * partial function accepts it. Wait time is bounded by the given duration,
   * with an AssertionFailure being thrown in case of timeout.
   *
   * Use this variant to implement more complicated or conditional
   * processing.
   *
   * @return the received object as transformed by the partial function
   */
  def expectMsgPF[T](max: Duration = Duration.MinusInf, hint: String = "")(f: PartialFunction[Any, T]): T = {
    val _max = if (max eq Duration.MinusInf) remaining else max.dilated
    val o = receiveOne(_max)
    assert(o ne null, "timeout during expectMsg: " + hint)
    assert(f.isDefinedAt(o), "does not match: " + o)
    f(o)
  }

  /**
   * Same as `expectMsgType[T](remaining)`, but correctly treating the timeFactor.
   */
  def expectMsgType[T](implicit m: Manifest[T]): T = expectMsgClass_internal(remaining, m.erasure.asInstanceOf[Class[T]])

  /**
   * Receive one message from the test actor and assert that it conforms to the
   * given type (after erasure). Wait time is bounded by the given duration,
   * with an AssertionFailure being thrown in case of timeout.
   *
   * @return the received object
   */
  def expectMsgType[T](max: Duration)(implicit m: Manifest[T]): T = expectMsgClass_internal(max.dilated, m.erasure.asInstanceOf[Class[T]])

  /**
   * Same as `expectMsgClass(remaining, c)`, but correctly treating the timeFactor.
   */
  def expectMsgClass[C](c: Class[C]): C = expectMsgClass_internal(remaining, c)

  /**
   * Receive one message from the test actor and assert that it conforms to
   * the given class. Wait time is bounded by the given duration, with an
   * AssertionFailure being thrown in case of timeout.
   *
   * @return the received object
   */
  def expectMsgClass[C](max: Duration, c: Class[C]): C = expectMsgClass_internal(max.dilated, c)

  private def expectMsgClass_internal[C](max: Duration, c: Class[C]): C = {
    val o = receiveOne(max)
    assert(o ne null, "timeout during expectMsgClass waiting for " + c)
    assert(c isInstance o, "expected " + c + ", found " + o.getClass)
    o.asInstanceOf[C]
  }

  /**
   * Same as `expectMsgAnyOf(remaining, obj...)`, but correctly treating the timeFactor.
   */
  def expectMsgAnyOf[T](obj: T*): T = expectMsgAnyOf_internal(remaining, obj: _*)

  /**
   * Receive one message from the test actor and assert that it equals one of
   * the given objects. Wait time is bounded by the given duration, with an
   * AssertionFailure being thrown in case of timeout.
   *
   * @return the received object
   */
  def expectMsgAnyOf[T](max: Duration, obj: T*): T = expectMsgAnyOf_internal(max.dilated, obj: _*)

  private def expectMsgAnyOf_internal[T](max: Duration, obj: T*): T = {
    val o = receiveOne(max)
    assert(o ne null, "timeout during expectMsgAnyOf waiting for " + obj.mkString("(", ", ", ")"))
    assert(obj exists (_ == o), "found unexpected " + o)
    o.asInstanceOf[T]
  }

  /**
   * Same as `expectMsgAnyClassOf(remaining, obj...)`, but correctly treating the timeFactor.
   */
  def expectMsgAnyClassOf[C](obj: Class[_ <: C]*): C = expectMsgAnyClassOf_internal(remaining, obj: _*)

  /**
   * Receive one message from the test actor and assert that it conforms to
   * one of the given classes. Wait time is bounded by the given duration,
   * with an AssertionFailure being thrown in case of timeout.
   *
   * @return the received object
   */
  def expectMsgAnyClassOf[C](max: Duration, obj: Class[_ <: C]*): C = expectMsgAnyClassOf_internal(max.dilated, obj: _*)

  private def expectMsgAnyClassOf_internal[C](max: Duration, obj: Class[_ <: C]*): C = {
    val o = receiveOne(max)
    assert(o ne null, "timeout during expectMsgAnyClassOf waiting for " + obj.mkString("(", ", ", ")"))
    assert(obj exists (_ isInstance o), "found unexpected " + o)
    o.asInstanceOf[C]
  }

  /**
   * Same as `expectMsgAllOf(remaining, obj...)`, but correctly treating the timeFactor.
   */
  def expectMsgAllOf[T](obj: T*): Seq[T] = expectMsgAllOf_internal(remaining, obj: _*)

  /**
   * Receive a number of messages from the test actor matching the given
   * number of objects and assert that for each given object one is received
   * which equals it and vice versa. This construct is useful when the order in
   * which the objects are received is not fixed. Wait time is bounded by the
   * given duration, with an AssertionFailure being thrown in case of timeout.
   *
   * <pre>
   *   dispatcher ! SomeWork1()
   *   dispatcher ! SomeWork2()
   *   expectMsgAllOf(1 second, Result1(), Result2())
   * </pre>
   */
  def expectMsgAllOf[T](max: Duration, obj: T*): Seq[T] = expectMsgAllOf_internal(max.dilated, obj: _*)

  private def expectMsgAllOf_internal[T](max: Duration, obj: T*): Seq[T] = {
    val recv = receiveN_internal(obj.size, max)
    obj foreach (x ⇒ assert(recv exists (x == _), "not found " + x))
    recv foreach (x ⇒ assert(obj exists (x == _), "found unexpected " + x))
    recv.asInstanceOf[Seq[T]]
  }

  /**
   * Same as `expectMsgAllClassOf(remaining, obj...)`, but correctly treating the timeFactor.
   */
  def expectMsgAllClassOf[T](obj: Class[_ <: T]*): Seq[T] = expectMsgAllClassOf_internal(remaining, obj: _*)

  /**
   * Receive a number of messages from the test actor matching the given
   * number of classes and assert that for each given class one is received
   * which is of that class (equality, not conformance). This construct is
   * useful when the order in which the objects are received is not fixed.
   * Wait time is bounded by the given duration, with an AssertionFailure
   * being thrown in case of timeout.
   */
  def expectMsgAllClassOf[T](max: Duration, obj: Class[_ <: T]*): Seq[T] = expectMsgAllClassOf_internal(max.dilated, obj: _*)

  private def expectMsgAllClassOf_internal[T](max: Duration, obj: Class[_ <: T]*): Seq[T] = {
    val recv = receiveN_internal(obj.size, max)
    obj foreach (x ⇒ assert(recv exists (_.getClass eq x), "not found " + x))
    recv foreach (x ⇒ assert(obj exists (_ eq x.getClass), "found non-matching object " + x))
    recv.asInstanceOf[Seq[T]]
  }

  /**
   * Same as `expectMsgAllConformingOf(remaining, obj...)`, but correctly treating the timeFactor.
   */
  def expectMsgAllConformingOf[T](obj: Class[_ <: T]*): Seq[T] = expectMsgAllClassOf_internal(remaining, obj: _*)

  /**
   * Receive a number of messages from the test actor matching the given
   * number of classes and assert that for each given class one is received
   * which conforms to that class (and vice versa). This construct is useful
   * when the order in which the objects are received is not fixed.  Wait time
   * is bounded by the given duration, with an AssertionFailure being thrown in
   * case of timeout.
   *
   * Beware that one object may satisfy all given class constraints, which
   * may be counter-intuitive.
   */
  def expectMsgAllConformingOf[T](max: Duration, obj: Class[_ <: T]*): Seq[T] = expectMsgAllConformingOf(max.dilated, obj: _*)

  private def expectMsgAllConformingOf_internal[T](max: Duration, obj: Class[_ <: T]*): Seq[T] = {
    val recv = receiveN_internal(obj.size, max)
    obj foreach (x ⇒ assert(recv exists (x isInstance _), "not found " + x))
    recv foreach (x ⇒ assert(obj exists (_ isInstance x), "found non-matching object " + x))
    recv.asInstanceOf[Seq[T]]
  }

  /**
   * Same as `expectNoMsg(remaining)`, but correctly treating the timeFactor.
   */
  def expectNoMsg() { expectNoMsg_internal(remaining) }

  /**
   * Assert that no message is received for the specified time.
   */
  def expectNoMsg(max: Duration) { expectNoMsg_internal(max.dilated) }

  private def expectNoMsg_internal(max: Duration) {
    val o = receiveOne(max)
    assert(o eq null, "received unexpected message " + o)
    lastWasNoMsg = true
  }

  /**
   * Same as `receiveWhile(remaining)(f)`, but correctly treating the timeFactor.
   */
  @deprecated("insert empty first parameter list: receiveWhile()(pf)", "1.2")
  def receiveWhile[T](f: PartialFunction[AnyRef, T]): Seq[T] = receiveWhile(remaining / Duration.timeFactor)(f)

  /**
   * Receive a series of messages until one does not match the given partial
   * function or the idle timeout is met (disabled by default) or the overall
   * maximum duration is elapsed. Returns the sequence of messages.
   *
   * Note that it is not an error to hit the `max` duration in this case.
   *
   * One possible use of this method is for testing whether messages of
   * certain characteristics are generated at a certain rate:
   *
   * <pre>
   * test ! ScheduleTicks(100 millis)
   * val series = receiveWhile(750 millis) {
   *     case Tick(count) => count
   * }
   * assert(series == (1 to 7).toList)
   * </pre>
   */
  def receiveWhile[T](max: Duration = Duration.MinusInf, idle: Duration = Duration.Inf)(f: PartialFunction[AnyRef, T]): Seq[T] = {
    val stop = now + (if (max == Duration.MinusInf) remaining else max.dilated)
    var msg: Message = NullMessage

    @tailrec
    def doit(acc: List[T]): List[T] = {
      receiveOne((stop - now) min idle)
      lastMessage match {
        case NullMessage ⇒
          lastMessage = msg
          acc.reverse
        case RealMessage(o, _) if (f isDefinedAt o) ⇒
          msg = lastMessage
          doit(f(o) :: acc)
        case RealMessage(o, _) ⇒
          queue.offerFirst(lastMessage)
          lastMessage = msg
          acc.reverse
      }
    }

    val ret = doit(Nil)
    lastWasNoMsg = true
    ret
  }

  /**
   * Same as `receiveN(n, remaining)` but correctly taking into account
   * Duration.timeFactor.
   */
  def receiveN(n: Int): Seq[AnyRef] = receiveN_internal(n, remaining)

  /**
   * Receive N messages in a row before the given deadline.
   */
  def receiveN(n: Int, max: Duration): Seq[AnyRef] = receiveN_internal(n, max.dilated)

  private def receiveN_internal(n: Int, max: Duration): Seq[AnyRef] = {
    val stop = max + now
    for { x ← 1 to n } yield {
      val timeout = stop - now
      val o = receiveOne(timeout)
      assert(o ne null, "timeout while expecting " + n + " messages")
      o
    }
  }

  /**
   * Receive one message from the internal queue of the TestActor. If the given
   * duration is zero, the queue is polled (non-blocking).
   *
   * This method does NOT automatically scale its Duration parameter!
   */
  def receiveOne(max: Duration): AnyRef = {
    val message =
      if (max == 0.seconds) {
        queue.pollFirst
      } else if (max.finite_?) {
        queue.pollFirst(max.length, max.unit)
      } else {
        queue.takeFirst
      }
    lastWasNoMsg = false
    message match {
      case null ⇒
        lastMessage = NullMessage
        null
      case RealMessage(msg, _) ⇒
        lastMessage = message
        msg
    }
  }

  private def format(u: TimeUnit, d: Duration) = "%.3f %s".format(d.toUnit(u), u.toString.toLowerCase)
}

trait TestKit extends TestKitLight {
  implicit val self = testActor
}

/**
 * TestKit-based probe which allows sending, reception and reply.
 */
class TestProbe extends TestKit {

  /**
   * Shorthand to get the testActor.
   */
  def ref = testActor

  /**
   * Send message to an actor while using the probe's TestActor as the sender.
   * Replies will be available for inspection with all of TestKit's assertion
   * methods.
   */
  def send(actor: ActorRef, msg: AnyRef) = {
    actor.!(msg)(testActor)
  }

  /**
   * Forward this message as if in the TestActor's receive method with self.forward.
   */
  def forward(actor: ActorRef, msg: AnyRef = lastMessage.msg) {
    actor.!(msg)(lastMessage.channel)
  }

  /**
   * Get channel of last received message.
   */
  def channel = lastMessage.channel

}

object TestProbe {
  def apply() = new TestProbe
}
