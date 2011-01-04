package akka.util

import akka.actor.{Actor, FSM}
import Actor._
import duration._

import java.util.concurrent.{BlockingDeque, LinkedBlockingDeque, TimeUnit}

import scala.annotation.tailrec

object TestActor {
  type Ignore = Option[PartialFunction[AnyRef, Boolean]]

  case class SetTimeout(d : Duration)
  case class SetIgnore(i : Ignore)
}

class TestActor(queue : BlockingDeque[AnyRef]) extends Actor with FSM[Int, TestActor.Ignore] {
  import FSM._
  import TestActor._

  startWith(0, None)
  when(0, stateTimeout = 5 seconds) {
    case Ev(SetTimeout(d)) =>
      setStateTimeout(0, if (d.finite_?) d else None)
      stay
    case Ev(SetIgnore(ign)) => stay using ign
    case Ev(StateTimeout) =>
      stop
    case Event(x : AnyRef, ign) =>
      val ignore = ign map (z => if (z isDefinedAt x) z(x) else false) getOrElse false
      if (!ignore) {
        queue.offerLast(x)
      }
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
 *     val test = actorOf[SomeActor].start
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
 * @author Roland Kuhn
 * @since 1.1
 */
trait TestKit {

  private val queue = new LinkedBlockingDeque[AnyRef]()
  
  /**
   * ActorRef of the test actor. Access is provided to enable e.g.
   * registration as message target.
   */
  protected val testActor = actorOf(new TestActor(queue)).start

  /**
   * Implicit sender reference so that replies are possible for messages sent
   * from the test class.
   */
  protected implicit val senderOption = Some(testActor)

  private var end : Duration = Duration.Inf
  /*
   * THIS IS A HACK: expectNoMsg and receiveWhile are bounded by `end`, but
   * running them should not trigger an AssertionError, so mark their end
   * time here and do not fail at the end of `within` if that time is not
   * long gone.
   */
  private var lastSoftTimeout : Duration = now - 5.millis

  /**
   * Stop test actor. Should be done at the end of the test unless relying on
   * test actor timeout.
   */
  def stopTestActor { testActor.stop }

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
  def setTestActorTimeout(d : Duration) { testActor ! TestActor.SetTimeout(d) }

  /**
   * Ignore all messages in the test actor for which the given partial
   * function returns true.
   */
  def ignoreMsg(f : PartialFunction[AnyRef, Boolean]) { testActor ! TestActor.SetIgnore(Some(f)) }

  /**
   * Stop ignoring messages in the test actor.
   */
  def ignoreNoMsg { testActor ! TestActor.SetIgnore(None) }

  /**
   * Obtain current time (`System.currentTimeMillis`) as Duration.
   */
  def now : Duration = System.nanoTime.nanos

  /**
   * Obtain time remaining for execution of the innermost enclosing `within` block.
   */
  def remaining : Duration = end - now

  /**
   * Execute code block while bounding its execution time between `min` and
   * `max`. `within` blocks may be nested. All methods in this trait which
   * take maximum wait times are available in a version which implicitly uses
   * the remaining time governed by the innermost enclosing `within` block.
   *
   * <pre>
   * val ret = within(50 millis) {
   *         test ! "ping"
   *         expectMsgClass(classOf[String])
   *       }
   * </pre>
   */
  def within[T](min : Duration, max : Duration)(f : => T) : T = {
    val start = now
    val rem = end - start
    assert (rem >= min, "required min time "+min+" not possible, only "+format(min.unit, rem)+" left")

    val max_diff = if (max < rem) max else rem
    val prev_end = end
    end = start + max_diff

    val ret = f

    val diff = now - start
    assert (min <= diff, "block took "+format(min.unit, diff)+", should at least have been "+min)
    /*
     * caution: HACK AHEAD
     */
    if (now - lastSoftTimeout > 5.millis) {
      assert (diff <= max_diff, "block took "+format(max.unit, diff)+", exceeding "+format(max.unit, max_diff))
    } else {
      lastSoftTimeout -= 5.millis
    }

    end = prev_end
    ret
  }

  /**
   * Same as calling `within(0 seconds, max)(f)`.
   */
  def within[T](max : Duration)(f : => T) : T = within(0 seconds, max)(f)

  /**
   * Same as `expectMsg`, but takes the maximum wait time from the innermost
   * enclosing `within` block.
   */
  def expectMsg(obj : Any) : AnyRef = expectMsg(remaining, obj)

  /**
   * Receive one message from the test actor and assert that it equals the
   * given object. Wait time is bounded by the given duration, with an
   * AssertionFailure being thrown in case of timeout.
   *
   * @return the received object
   */
  def expectMsg(max : Duration, obj : Any) : AnyRef = {
    val o = receiveOne(max)
    assert (o ne null, "timeout during expectMsg")
    assert (obj == o, "expected "+obj+", found "+o)
    o
  }

  /**
   * Same as `expectMsg`, but takes the maximum wait time from the innermost
   * enclosing `within` block.
   */
  def expectMsg[T](f : PartialFunction[Any, T]) : T = expectMsg(remaining)(f)

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
  def expectMsg[T](max : Duration)(f : PartialFunction[Any, T]) : T = {
    val o = receiveOne(max)
    assert (o ne null, "timeout during expectMsg")
    assert (f.isDefinedAt(o), "does not match: "+o)
    f(o)
  }

  /**
   * Same as `expectMsgClass`, but takes the maximum wait time from the innermost
   * enclosing `within` block.
   */
  def expectMsgClass[C](c : Class[C]) : C = expectMsgClass(remaining, c)

  /**
   * Receive one message from the test actor and assert that it conforms to
   * the given class. Wait time is bounded by the given duration, with an
   * AssertionFailure being thrown in case of timeout.
   *
   * @return the received object
   */
  def expectMsgClass[C](max : Duration, c : Class[C]) : C = {
    val o = receiveOne(max)
    assert (o ne null, "timeout during expectMsgClass")
    assert (c isInstance o, "expected "+c+", found "+o.getClass)
    o.asInstanceOf[C]
  }

  /**
   * Same as `expectMsgAnyOf`, but takes the maximum wait time from the innermost
   * enclosing `within` block.
   */
  def expectMsgAnyOf(obj : Any*) : AnyRef = expectMsgAnyOf(remaining, obj : _*)

  /**
   * Receive one message from the test actor and assert that it equals one of
   * the given objects. Wait time is bounded by the given duration, with an
   * AssertionFailure being thrown in case of timeout.
   *
   * @return the received object
   */
  def expectMsgAnyOf(max : Duration, obj : Any*) : AnyRef = {
    val o = receiveOne(max)
    assert (o ne null, "timeout during expectMsgAnyOf")
    assert (obj exists (_ == o), "found unexpected "+o)
    o
  }

  /**
   * Same as `expectMsgAnyClassOf`, but takes the maximum wait time from the innermost
   * enclosing `within` block.
   */
  def expectMsgAnyClassOf(obj : Class[_]*) : AnyRef = expectMsgAnyClassOf(remaining, obj : _*)

  /**
   * Receive one message from the test actor and assert that it conforms to
   * one of the given classes. Wait time is bounded by the given duration,
   * with an AssertionFailure being thrown in case of timeout.
   *
   * @return the received object
   */
  def expectMsgAnyClassOf(max : Duration, obj : Class[_]*) : AnyRef = {
    val o = receiveOne(max)
    assert (o ne null, "timeout during expectMsgAnyClassOf")
    assert (obj exists (_ isInstance o), "found unexpected "+o)
    o
  }

  /**
   * Same as `expectMsgAllOf`, but takes the maximum wait time from the innermost
   * enclosing `within` block.
   */
  def expectMsgAllOf(obj : Any*) { expectMsgAllOf(remaining, obj : _*) }

  /**
   * Receive a number of messages from the test actor matching the given
   * number of objects and assert that for each given object one is received
   * which equals it. This construct is useful when the order in which the
   * objects are received is not fixed. Wait time is bounded by the given
   * duration, with an AssertionFailure being thrown in case of timeout.
   *
   * <pre>
   * within(1 second) {
   *   dispatcher ! SomeWork1()
   *   dispatcher ! SomeWork2()
   *   expectMsgAllOf(Result1(), Result2())
   * }
   * </pre>
   */
  def expectMsgAllOf(max : Duration, obj : Any*) {
    val recv = receiveN(obj.size, now + max)
    assert (obj forall (x => recv exists (x == _)), "not found all")
  }

  /**
   * Same as `expectMsgAllClassOf`, but takes the maximum wait time from the innermost
   * enclosing `within` block.
   */
  def expectMsgAllClassOf(obj : Class[_]*) { expectMsgAllClassOf(remaining, obj : _*) }

  /**
   * Receive a number of messages from the test actor matching the given
   * number of classes and assert that for each given class one is received
   * which is of that class (equality, not conformance). This construct is
   * useful when the order in which the objects are received is not fixed.
   * Wait time is bounded by the given duration, with an AssertionFailure
   * being thrown in case of timeout.
   */
  def expectMsgAllClassOf(max : Duration, obj : Class[_]*) {
    val recv = receiveN(obj.size, now + max)
    assert (obj forall (x => recv exists (_.getClass eq x)), "not found all")
  }

  /**
   * Same as `expectMsgAllConformingOf`, but takes the maximum wait time from the innermost
   * enclosing `within` block.
   */
  def expectMsgAllConformingOf(obj : Class[_]*) { expectMsgAllClassOf(remaining, obj : _*) }

  /**
   * Receive a number of messages from the test actor matching the given
   * number of classes and assert that for each given class one is received
   * which conforms to that class. This construct is useful when the order in
   * which the objects are received is not fixed.  Wait time is bounded by
   * the given duration, with an AssertionFailure being thrown in case of
   * timeout.
   *
   * Beware that one object may satisfy all given class constraints, which
   * may be counter-intuitive.
   */
  def expectMsgAllConformingOf(max : Duration, obj : Class[_]*) {
    val recv = receiveN(obj.size, now + max)
    assert (obj forall (x => recv exists (x isInstance _)), "not found all")
  }

  /**
   * Same as `expectNoMsg`, but takes the maximum wait time from the innermost
   * enclosing `within` block.
   */
  def expectNoMsg { expectNoMsg(remaining) }

  /**
   * Assert that no message is received for the specified time.
   */
  def expectNoMsg(max : Duration) {
    val o = receiveOne(max)
    assert (o eq null, "received unexpected message "+o)
    lastSoftTimeout = now
  }

  /**
   * Same as `receiveWhile`, but takes the maximum wait time from the innermost
   * enclosing `within` block.
   */
  def receiveWhile[T](f : PartialFunction[AnyRef, T]) : Seq[T] = receiveWhile(remaining)(f)

  /**
   * Receive a series of messages as long as the given partial function
   * accepts them or the idle timeout is met or the overall maximum duration
   * is elapsed. Returns the sequence of messages.
   *
   * Beware that the maximum duration is not implicitly bounded by or taken
   * from the innermost enclosing `within` block, as it is not an error to
   * hit the `max` duration in this case.
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
  def receiveWhile[T](max : Duration)(f : PartialFunction[AnyRef, T]) : Seq[T] = {
    val stop = now + max

    @tailrec def doit(acc : List[T]) : List[T] = {
      receiveOne(stop - now) match {
        case null =>
          acc.reverse
        case o if (f isDefinedAt o) =>
          doit(f(o) :: acc)
        case o =>
          queue.offerFirst(o)
          acc.reverse
      }
    }

    val ret = doit(Nil)
    lastSoftTimeout = now
    ret
  }

  private def receiveN(n : Int, stop : Duration) : Seq[AnyRef] = {
    for { x <- 1 to n } yield {
      val timeout = stop - now
      val o = receiveOne(timeout)
      assert (o ne null, "timeout while expecting "+n+" messages")
      o
    }
  }

  private def receiveOne(max : Duration) : AnyRef = {
    if (max == 0.seconds) {
      queue.pollFirst
    } else if (max.finite_?) {
      queue.pollFirst(max.length, max.unit)
    } else {
      queue.takeFirst
    }
  }

  private def format(u : TimeUnit, d : Duration) = "%.3f %s".format(d.toUnit(u), u.toString.toLowerCase)
}

// vim: set ts=2 sw=2 et:
