package akka.dispatch

import org.scalatest.junit.JUnitSuite
import org.junit.Test
import Future.flow
import akka.util.cps._
import akka.actor.Timeout
import akka.util.duration._

class PromiseStreamSpec extends JUnitSuite {
  @Test
  def simpleTest {
    val a, b, c = Promise[Int]()
    val q = PromiseStream[Int]()
    flow { q << (1, 2, 3) }
    flow {
      a << q()
      b << q
      c << q()
    }
    assert(a.get === 1)
    assert(b.get === 2)
    assert(c.get === 3)
  }

  @Test
  def pendingTest {
    val a, b, c = Promise[Int]()
    val q = PromiseStream[Int]()
    flow {
      a << q
      b << q()
      c << q
    }
    flow { q <<< List(1, 2, 3) }
    assert(a.get === 1)
    assert(b.get === 2)
    assert(c.get === 3)
  }

  @Test
  def timeoutTest {
    val a, c = Promise[Int]()
    val b = Promise[Int](0)
    val q = PromiseStream[Int](1000)
    flow {
      a << q()
      b << q()
      c << q()
    }
    Thread.sleep(10)
    flow {
      q << (1, 2)
      q << 3
    }
    assert(a.get === 1)
    intercept[FutureTimeoutException] { b.get }
    assert(c.get === 3)
  }

  @Test
  def timeoutTest2 {
    val q = PromiseStream[Int](500)
    val a = q.dequeue()
    val b = q.dequeue()
    q += 1
    Thread.sleep(500)
    q += (2, 3)
    val c = q.dequeue()
    val d = q.dequeue()
    assert(a.get === 1)
    intercept[FutureTimeoutException] { b.get }
    assert(c.get === 2)
    assert(d.get === 3)
  }

  @Test
  def pendingTest2 {
    val a, b, c, d = Promise[Int]()
    val q1, q2 = PromiseStream[Int]()
    val oneTwo = Future(List(1, 2))
    flow {
      a << q2
      b << q2
      q1 << 3 << 4
    }
    flow {
      q2 <<< oneTwo
      c << q1
      d << q1
    }
    assert(a.get === 1)
    assert(b.get === 2)
    assert(c.get === 3)
    assert(d.get === 4)
  }

  @Test
  def pendingEnqueueTest {
    val q = PromiseStream[Int]()
    val a = q.dequeue()
    val b = q.dequeue()
    val c, d = Promise[Int]()
    flow {
      c << q
      d << q
    }
    q ++= List(1, 2, 3, 4)

    assert(a.get === 1)
    assert(b.get === 2)
    assert(c.get === 3)
    assert(d.get === 4)
  }

  @Test
  def mapTest {
    val qs = PromiseStream[String]()
    val qi = qs.map(_.length)
    val a, c = Promise[Int]()
    val b = Promise[String]()
    flow {
      a << qi
      b << qs
      c << qi
    }
    flow {
      qs << ("Hello", "World!", "Test")
    }
    assert(a.get === 5)
    assert(b.get === "World!")
    assert(c.get === 4)
  }

  @Test
  def concurrentStressTest {
    implicit val timeout = Timeout(60 seconds)
    val q = PromiseStream[Long](timeout.duration.toMillis)

    flow {
      var n = 0L
      repeatC(50000) {
        n += 1
        q << n
      }
    }

    val future = Future sequence {
      List.fill(10) {
        flow {
          var total = 0L
          repeatC(10000) {
            val n = q()
            total += n
          }
          total
        }
      }
    } map (_.sum)

    flow {
      var n = 50000L
      repeatC(50000) {
        n += 1
        q << n
      }
    }

    val result = future.get
    assert(result === (1L to 100000L).sum)
  }
}
