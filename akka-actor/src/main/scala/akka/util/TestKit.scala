package akka.util

import akka.actor.{Actor, FSM}
import Actor._
import duration._

import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue}

object TestActor {
    case class SetTimeout(d : Duration)
}

class TestActor(queue : BlockingQueue[AnyRef]) extends Actor with FSM[Int, Null] {
    import FSM._
    import TestActor._

    startWith(0, null)
    when(0, stateTimeout = 5 seconds) {
        case Event(SetTimeout(d), _) =>
            setStateTimeout(0, if (d.finite_?) d else None)
            stay
        case Event(StateTimeout, _) =>
            stop
        case Event(x : AnyRef, _) =>
            queue offer x
            stay
    }
    initialize
}

/**
 * Test kit for testing actors. Inheriting from this trait enables reception of
 * replies from actors, which are queued by an internal actor and can be
 * examined using the `expect...` methods. Assertions and bounds concerning
 * timing are available in the form of `within` blocks.
 *
 * <pre>
 * class Test extends TestKit {
 *     val test = actorOf[SomeActor].start
 *
 *     within (1 second) {
 *         test ! SomeWork
 *         expect(Result1) // bounded to 1 second
 *         expect(Result2) // bounded to the remainder of the 1 second
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
 *    care not to run tests within a single test class in parallel.
 */
trait TestKit {

    private val queue = new LinkedBlockingQueue[AnyRef]()
    
    private val sender = actorOf(new TestActor(queue)).start
    protected implicit val senderOption = Some(sender)

    private var end : Duration = Duration.Inf

    /**
     * Stop test actor. Should be done at the end of the test unless relying on
     * test actor timeout.
     */
    def stopTestActor { sender.stop }

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
    def setTestActorTimeout(d : Duration) { sender ! TestActor.SetTimeout(d) }

    /**
     * Obtain current time (`System.currentTimeMillis`) as Duration.
     */
    def now : Duration = System.currentTimeMillis.millis

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
     *             test ! "ping"
     *             expectClass(classOf[String])
     *           }
     * </pre>
     */
    def within[T](min : Duration, max : Duration)(f : => T) : T = {
        val start = now
        val rem = end - start
        assert (rem >= min, "required min time "+min+" not possible, only "+rem+" left")

        val max_diff = if (max < rem) max else rem
        val prev_end = end
        end = start + max_diff

        val ret = f

        val diff = now - start
        assert (min <= diff, "block took "+diff+", should at least have been "+min)
        assert (diff <= max_diff, "block took "+diff+", exceeding "+max_diff)

        end = prev_end
        ret
    }

    /**
     * Same as calling `within(0 seconds, max)(f)`.
     */
    def within[T](max : Duration)(f : => T) : T = within(0 seconds, max)(f)

    /**
     * Same as `expect`, but takes the maximum wait time from the innermost
     * enclosing `within` block.
     */
    def expect(obj : Any) : AnyRef = expect(remaining, obj)

    /**
     * Receive one message from the test actor and assert that it equals the
     * given object. Wait time is bounded by the given duration, with an
     * AssertionFailure being thrown in case of timeout.
     *
     * @return the received object
     */
    def expect(max : Duration, obj : Any) : AnyRef = {
        val o = if (max.finite_?) queue.poll(max.length, max.unit) else queue.take
        assert (o ne null, "timeout during expect")
        assert (obj == o, "expected "+obj+", found "+o)
        o
    }

    /**
     * Same as `expect`, but takes the maximum wait time from the innermost
     * enclosing `within` block.
     */
    def expect[T](f : PartialFunction[Any, T]) : T = expect(remaining)(f)

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
    def expect[T](max : Duration)(f : PartialFunction[Any, T]) : T = {
        val o = if (max.finite_?) queue.poll(max.length, max.unit) else queue.take
        assert (o ne null, "timeout during expect")
        assert (f.isDefinedAt(o), "does not match: "+o)
        f(o)
    }

    /**
     * Same as `expectClass`, but takes the maximum wait time from the innermost
     * enclosing `within` block.
     */
    def expectClass[C](c : Class[C]) : C = expectClass(remaining, c)

    /**
     * Receive one message from the test actor and assert that it conforms to
     * the given class. Wait time is bounded by the given duration, with an
     * AssertionFailure being thrown in case of timeout.
     *
     * @return the received object
     */
    def expectClass[C](max : Duration, c : Class[C]) : C = {
        val o = if (max.finite_?) queue.poll(max.length, max.unit) else queue.take
        assert (o ne null, "timeout during expectClass")
        assert (c isInstance o, "expected "+c+", found "+o.getClass)
        o.asInstanceOf[C]
    }

    /**
     * Same as `expectAnyOf`, but takes the maximum wait time from the innermost
     * enclosing `within` block.
     */
    def expectAnyOf(obj : Any*) : AnyRef = expectAnyOf(remaining, obj : _*)

    /**
     * Receive one message from the test actor and assert that it equals one of
     * the given objects. Wait time is bounded by the given duration, with an
     * AssertionFailure being thrown in case of timeout.
     *
     * @return the received object
     */
    def expectAnyOf(max : Duration, obj : Any*) : AnyRef = {
        val o = if (max.finite_?) queue.poll(max.length, max.unit) else queue.take
        assert (o ne null, "timeout during expectAnyOf")
        assert (obj exists (_ == o), "found unexpected "+o)
        o
    }

    /**
     * Same as `expectAnyClassOf`, but takes the maximum wait time from the innermost
     * enclosing `within` block.
     */
    def expectAnyClassOf(obj : Class[_]*) : AnyRef = expectAnyClassOf(remaining, obj : _*)

    /**
     * Receive one message from the test actor and assert that it conforms to
     * one of the given classes. Wait time is bounded by the given duration,
     * with an AssertionFailure being thrown in case of timeout.
     *
     * @return the received object
     */
    def expectAnyClassOf(max : Duration, obj : Class[_]*) : AnyRef = {
        val o = if (max.finite_?) queue.poll(max.length, max.unit) else queue.take
        assert (o ne null, "timeout during expectAnyClassOf")
        assert (obj exists (_ isInstance o), "found unexpected "+o)
        o
    }

    /**
     * Same as `expectAllOf`, but takes the maximum wait time from the innermost
     * enclosing `within` block.
     */
    def expectAllOf(obj : Any*) { expectAllOf(remaining, obj : _*) }

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
     *   expectAllOf(Result1(), Result2())
     * }
     * </pre>
     */
    def expectAllOf(max : Duration, obj : Any*) {
        val stop = now + max
        val recv = for { x <- 1 to obj.size } yield {
            val timeout = stop - now
            val o = queue.poll(timeout.length, timeout.unit)
            assert (o ne null, "timeout during expectAllClassOf")
            o
        }
        assert (obj forall (x => recv exists (x == _)), "not found all")
    }

    /**
     * Same as `expectAllClassOf`, but takes the maximum wait time from the innermost
     * enclosing `within` block.
     */
    def expectAllClassOf(obj : Class[_]*) { expectAllClassOf(remaining, obj : _*) }

    /**
     * Receive a number of messages from the test actor matching the given
     * number of classes and assert that for each given class one is received
     * which is of that class (equality, not conformance). This construct is
     * useful when the order in which the objects are received is not fixed.
     * Wait time is bounded by the given duration, with an AssertionFailure
     * being thrown in case of timeout.
     */
    def expectAllClassOf(max : Duration, obj : Class[_]*) {
        val stop = now + max
        val recv = for { x <- 1 to obj.size } yield {
            val timeout = stop - now
            val o = queue.poll(timeout.length, timeout.unit)
            assert (o ne null, "timeout during expectAllClassOf")
            o
        }
        assert (obj forall (x => recv exists (_.getClass eq x)), "not found all")
    }

    /**
     * Same as `expectAllConformingOf`, but takes the maximum wait time from the innermost
     * enclosing `within` block.
     */
    def expectAllConformingOf(obj : Class[_]*) { expectAllClassOf(remaining, obj : _*) }

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
    def expectAllConformingOf(max : Duration, obj : Class[_]*) {
        val stop = now + max
        val recv = for { x <- 1 to obj.size } yield {
            val timeout = stop - now
            val o = queue.poll(timeout.length, timeout.unit)
            assert (o ne null, "timeout during expectAllClassOf")
            o
        }
        assert (obj forall (x => recv exists (x isInstance _)), "not found all")
    }
}

// vim: set ts=4 sw=4 et:
