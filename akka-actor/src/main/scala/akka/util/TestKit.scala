package akka.util

import akka.actor.{Actor, FSM}
import Actor._
import duration._

import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue}

class TestActor(queue : BlockingQueue[AnyRef]) extends Actor with FSM[Int, Null] {
    import FSM._

    startWith(0, null)
    when(0, stateTimeout = 5 seconds) {
        case Event(StateTimeout, _) => stop
        case Event(x : AnyRef, _) =>
            queue offer x
            stay
    }
    initialize
}

trait TestKit extends Logging {

    private val queue = new LinkedBlockingQueue[AnyRef]()
    
    val sender = actorOf(new TestActor(queue)).start
    implicit val senderOption = Some(sender)

    def within[T](max : Duration)(f : => T) : T = {
        val start = now
        val ret = f
        val stop = now
        val diff = stop - start
        assert (diff <= max, "block took "+diff+" instead of "+max)
        ret
    }

    def within[T](min : Duration, max : Duration)(f : => T) : T = {
        val start = now
        val ret = f
        val stop = now
        val diff = stop - start
        assert (diff >= min && diff <= max, "block took "+diff+", which is not in ("+min+","+max+")")
        ret
    }

    def expect(max : Duration, obj : Any) = {
        val o = if (max.finite_?) queue.poll(max.length, max.unit) else queue.take
        assert (obj == o, "expected "+obj+", found "+o)
        o
    }

    def expectAnyOf(max : Duration, obj : Any*) = {
        val o = if (max.finite_?) queue.poll(max.length, max.unit) else queue.take
        assert (obj exists (_ == o), "found unexpected "+o)
        o
    }

    def expectAllOf(max : Duration, obj : Any*) {
        val end = now + max
        val recv = for {
            x <- 1 to obj.size
            timeout = end - now
        } yield queue.poll(timeout.length, timeout.unit)
        assert (obj forall (x => recv exists (x == _)), "not found all")
    }

    def now = System.currentTimeMillis.millis
}

// vim: set ts=4 sw=4 et:
