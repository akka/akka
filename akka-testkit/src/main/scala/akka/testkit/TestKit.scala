/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit

import language.postfixOps

import akka.actor._
import Actor._
import akka.util.Duration
import akka.util.duration._
import java.util.concurrent.{ BlockingDeque, LinkedBlockingDeque, TimeUnit, atomic }
import atomic.AtomicInteger
import scala.annotation.tailrec
import akka.actor.ActorSystem
import akka.util.Timeout
import akka.util.BoxedType

object TestActor {
  type Ignore = Option[PartialFunction[AnyRef, Boolean]]

  trait AutoPilot {
    def run(sender: ActorRef, msg: Any): Option[AutoPilot]
  }

  case class SetIgnore(i: Ignore)
  case class Watch(ref: ActorRef)
  case class UnWatch(ref: ActorRef)
  case class SetAutoPilot(ap: AutoPilot)

  trait Message {
    def msg: AnyRef
    def sender: ActorRef
  }
  case class RealMessage(msg: AnyRef, sender: ActorRef) extends Message
  case object NullMessage extends Message {
    override def msg: AnyRef = throw new IllegalActorStateException("last receive did not dequeue a message")
    override def sender: ActorRef = throw new IllegalActorStateException("last receive did not dequeue a message")
  }
}

class TestActor(queue: BlockingDeque[TestActor.Message]) extends Actor {
  import TestActor._

  var ignore: Ignore = None

  var autopilot: Option[AutoPilot] = None

  def receive = {
    case SetIgnore(ign)      ⇒ ignore = ign
    case x @ Watch(ref)      ⇒ context.watch(ref); queue.offerLast(RealMessage(x, self))
    case x @ UnWatch(ref)    ⇒ context.unwatch(ref); queue.offerLast(RealMessage(x, self))
    case SetAutoPilot(pilot) ⇒ autopilot = Some(pilot)
    case x: AnyRef ⇒
      autopilot = autopilot.flatMap(_.run(sender, x))
      val observe = ignore map (ignoreFunc ⇒ if (ignoreFunc isDefinedAt x) !ignoreFunc(x) else true) getOrElse true
      if (observe) queue.offerLast(RealMessage(x, sender))
  }

  override def postStop() = {
    import scala.collection.JavaConverters._
    queue.asScala foreach { m ⇒ context.system.deadLetters ! DeadLetter(m.msg, m.sender, self) }
  }
}

/**
 * Implementation trait behind the [[akka.testkit.TestKit]] class: you may use
 * this if inheriting from a concrete class is not possible.
 *
 * <b>Use of the trait is discouraged because of potential issues with binary
 * backwards compatibility in the future, use at own risk.</b>
 *
 * This trait requires the concrete class mixing it in to provide an
 * [[akka.actor.ActorSystem]] which is available before this traits’s
 * constructor is run. The recommended way is this:
 *
 * {{{
 * class MyTest extends TestKitBase {
 *   implicit lazy val system = ActorSystem() // may add arguments here
 *   ...
 * }
 * }}}
 */
trait TestKitBase {

  import TestActor.{ Message, RealMessage, NullMessage }

  implicit val system: ActorSystem
  val testKitSettings = TestKitExtension(system)

  private val queue = new LinkedBlockingDeque[Message]()
  private[akka] var lastMessage: Message = NullMessage

  def lastSender = lastMessage.sender

  /**
   * ActorRef of the test actor. Access is provided to enable e.g.
   * registration as message target.
   */
  lazy val testActor: ActorRef = {
    val impl = system.asInstanceOf[ActorSystemImpl] //TODO ticket #1559
    impl.systemActorOf(Props(new TestActor(queue))
      .withDispatcher(CallingThreadDispatcher.Id),
      "testActor" + TestKit.testActorId.incrementAndGet)
  }

  private var end: Duration = Duration.Undefined

  /**
   * if last assertion was expectNoMsg, disable timing failure upon within()
   * block end.
   */
  private var lastWasNoMsg = false

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
   * Have the testActor watch someone (i.e. `context.watch(...)`). Waits until
   * the Watch message is received back using expectMsg.
   */
  def watch(ref: ActorRef) {
    val msg = TestActor.Watch(ref)
    testActor ! msg
    expectMsg(msg)
  }

  /**
   * Have the testActor stop watching someone (i.e. `context.unwatch(...)`). Waits until
   * the Watch message is received back using expectMsg.
   */
  def unwatch(ref: ActorRef) {
    val msg = TestActor.UnWatch(ref)
    testActor ! msg
    expectMsg(msg)
  }

  /**
   * Install an AutoPilot to drive the testActor: the AutoPilot will be run
   * for each received message and can be used to send or forward messages,
   * etc. Each invocation must return the AutoPilot for the next round.
   */
  def setAutoPilot(pilot: TestActor.AutoPilot): Unit = testActor ! TestActor.SetAutoPilot(pilot)

  /**
   * Obtain current time (`System.nanoTime`) as Duration.
   */
  def now: Duration = System.nanoTime.nanos

  /**
   * Obtain time remaining for execution of the innermost enclosing `within`
   * block or missing that it returns the properly dilated default for this
   * case from settings (key "akka.test.single-expect-default").
   */
  def remaining: Duration = remainingOr(testKitSettings.SingleExpectDefaultTimeout.dilated)

  /**
   * Obtain time remaining for execution of the innermost enclosing `within`
   * block or missing that it returns the given duration.
   */
  def remainingOr(duration: Duration): Duration = if (end == Duration.Undefined) duration else end - now

  /**
   * Query queue status.
   */
  def msgAvailable = !queue.isEmpty

  /**
   * Await until the given condition evaluates to `true` or the timeout
   * expires, whichever comes first.
   *
   * If no timeout is given, take it from the innermost enclosing `within`
   * block.
   *
   * Note that the timeout is scaled using Duration.dilated,
   * which uses the configuration entry "akka.test.timefactor".
   */
  def awaitCond(p: ⇒ Boolean, max: Duration = Duration.Undefined, interval: Duration = 100.millis) {
    val _max = if (max eq Duration.Undefined) remaining else max.dilated
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
   * Note that the timeout is scaled using Duration.dilated, which uses the
   * configuration entry "akka.test.timefactor", while the min Duration is not.
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
    val rem = if (end == Duration.Undefined) Duration.Inf else end - start
    assert(rem >= min, "required min time " + min + " not possible, only " + format(min.unit, rem) + " left")

    lastWasNoMsg = false

    val max_diff = _max min rem
    val prev_end = end
    end = start + max_diff

    val ret = try f finally end = prev_end

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
    assert(o ne null, "timeout (" + max + ") during expectMsg while waiting for " + obj)
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
  def expectMsgPF[T](max: Duration = Duration.Undefined, hint: String = "")(f: PartialFunction[Any, T]): T = {
    val _max = if (max eq Duration.Undefined) remaining else max.dilated
    val o = receiveOne(_max)
    assert(o ne null, "timeout (" + _max + ") during expectMsg: " + hint)
    assert(f.isDefinedAt(o), "expected: " + hint + " but got unexpected message " + o)
    f(o)
  }

  /**
   * Hybrid of expectMsgPF and receiveWhile: receive messages while the
   * partial function matches and returns false. Use it to ignore certain
   * messages while waiting for a specific message.
   *
   * @return the last received messsage, i.e. the first one for which the
   *         partial function returned true
   */
  def fishForMessage(max: Duration = Duration.Undefined, hint: String = "")(f: PartialFunction[Any, Boolean]): Any = {
    val _max = if (max eq Duration.Undefined) remaining else max.dilated
    val end = now + _max
    @tailrec
    def recv: Any = {
      val o = receiveOne(end - now)
      assert(o ne null, "timeout (" + _max + ") during fishForMessage, hint: " + hint)
      assert(f.isDefinedAt(o), "fishForMessage(" + hint + ") found unexpected message " + o)
      if (f(o)) o else recv
    }
    recv
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
    assert(o ne null, "timeout (" + max + ") during expectMsgClass waiting for " + c)
    assert(BoxedType(c) isInstance o, "expected " + c + ", found " + o.getClass)
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
    assert(o ne null, "timeout (" + max + ") during expectMsgAnyOf waiting for " + obj.mkString("(", ", ", ")"))
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
    assert(o ne null, "timeout (" + max + ") during expectMsgAnyClassOf waiting for " + obj.mkString("(", ", ", ")"))
    assert(obj exists (c ⇒ BoxedType(c) isInstance o), "found unexpected " + o)
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
    obj foreach (x ⇒ assert(recv exists (_.getClass eq BoxedType(x)), "not found " + x))
    recv foreach (x ⇒ assert(obj exists (c ⇒ BoxedType(c) eq x.getClass), "found non-matching object " + x))
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
    obj foreach (x ⇒ assert(recv exists (BoxedType(x) isInstance _), "not found " + x))
    recv foreach (x ⇒ assert(obj exists (c ⇒ BoxedType(c) isInstance x), "found non-matching object " + x))
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
  def receiveWhile[T](max: Duration = Duration.Undefined, idle: Duration = Duration.Inf, messages: Int = Int.MaxValue)(f: PartialFunction[AnyRef, T]): Seq[T] = {
    val stop = now + (if (max eq Duration.Undefined) remaining else max.dilated)
    var msg: Message = NullMessage

    @tailrec
    def doit(acc: List[T], count: Int): List[T] = {
      if (count >= messages) acc.reverse
      else {
        receiveOne((stop - now) min idle)
        lastMessage match {
          case NullMessage ⇒
            lastMessage = msg
            acc.reverse
          case RealMessage(o, _) if (f isDefinedAt o) ⇒
            msg = lastMessage
            doit(f(o) :: acc, count + 1)
          case RealMessage(o, _) ⇒
            queue.offerFirst(lastMessage)
            lastMessage = msg
            acc.reverse
        }
      }
    }

    val ret = doit(Nil, 0)
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
      assert(o ne null, "timeout (" + max + ") while expecting " + n + " messages")
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

/**
 * Test kit for testing actors. Inheriting from this trait enables reception of
 * replies from actors, which are queued by an internal actor and can be
 * examined using the `expectMsg...` methods. Assertions and bounds concerning
 * timing are available in the form of `within` blocks.
 *
 * <pre>
 * class Test extends TestKit(ActorSystem()) {
 *     try {
 *
 *       val test = system.actorOf(Props[SomeActor]
 *
 *       within (1 second) {
 *         test ! SomeWork
 *         expectMsg(Result1) // bounded to 1 second
 *         expectMsg(Result2) // bounded to the remainder of the 1 second
 *       }
 *
 *     } finally {
 *       system.shutdown()
 *     }
 * }
 * </pre>
 *
 * Beware of two points:
 *
 *  - the ActorSystem passed into the constructor needs to be shutdown,
 *    otherwise thread pools and memory will be leaked
 *  - this trait is not thread-safe (only one actor with one queue, one stack
 *    of `within` blocks); it is expected that the code is executed from a
 *    constructor as shown above, which makes this a non-issue, otherwise take
 *    care not to run tests within a single test class instance in parallel.
 *
 * It should be noted that for CI servers and the like all maximum Durations
 * are scaled using their Duration.dilated method, which uses the
 * TestKitExtension.Settings.TestTimeFactor settable via akka.conf entry "akka.test.timefactor".
 *
 * @author Roland Kuhn
 * @since 1.1
 */
class TestKit(_system: ActorSystem) extends { implicit val system = _system } with TestKitBase

object TestKit {
  private[testkit] val testActorId = new AtomicInteger(0)

  /**
   * Await until the given condition evaluates to `true` or the timeout
   * expires, whichever comes first.
   */
  def awaitCond(p: ⇒ Boolean, max: Duration, interval: Duration = 100.millis, noThrow: Boolean = false): Boolean = {
    val stop = now + max

    @tailrec
    def poll(): Boolean = {
      if (!p) {
        val toSleep = stop - now
        if (toSleep <= Duration.Zero) {
          if (noThrow) false
          else throw new AssertionError("timeout " + max + " expired")
        } else {
          Thread.sleep((toSleep min interval).toMillis)
          poll()
        }
      } else true
    }

    poll()
  }

  /**
   * Obtain current timestamp as Duration for relative measurements (using System.nanoTime).
   */
  def now: Duration = System.nanoTime().nanos

  /**
   * Java API. Scale timeouts (durations) during tests with the configured
   * 'akka.test.timefactor'.
   */
  def dilated(duration: Duration, system: ActorSystem): Duration =
    duration * TestKitExtension(system).TestTimeFactor
}

/**
 * TestKit-based probe which allows sending, reception and reply.
 */
class TestProbe(_application: ActorSystem) extends TestKit(_application) {

  /**
   * Shorthand to get the testActor.
   */
  def ref = testActor

  /**
   * Send message to an actor while using the probe's TestActor as the sender.
   * Replies will be available for inspection with all of TestKit's assertion
   * methods.
   */
  def send(actor: ActorRef, msg: Any): Unit = actor.!(msg)(testActor)

  /**
   * Forward this message as if in the TestActor's receive method with self.forward.
   */
  def forward(actor: ActorRef, msg: Any = lastMessage.msg): Unit = actor.!(msg)(lastMessage.sender)

  /**
   * Get sender of last received message.
   */
  def sender = lastMessage.sender

  /**
   * Send message to the sender of the last dequeued message.
   */
  def reply(msg: Any): Unit = sender.!(msg)(ref)

}

object TestProbe {
  def apply()(implicit system: ActorSystem) = new TestProbe(system)
}

trait ImplicitSender { this: TestKit ⇒
  implicit def self = testActor
}

trait DefaultTimeout { this: TestKit ⇒
  implicit val timeout: Timeout = testKitSettings.DefaultTimeout
}
