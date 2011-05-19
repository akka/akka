package akka.dispatch

import org.scalatest.junit.JUnitSuite
import org.junit.Test
import Future.flow

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
    val q = PromiseStream[Int]()
    val oneTwo = Future(List(1, 2))
    flow { a << q }
    flow {
      b << q
      q << 3 << 4
    }
    flow { c << q }
    flow {
      q <<< oneTwo
      d << q
    }
    assert((a.get, b.get, c.get, d.get) === (1, 2, 3, 4))
  }

  @Test
  def pendingEnqueueTest {
    val a, b = Promise[Int]()
    val q = PromiseStream[Int]()
    flow { a << q }
    flow { b << q }
    val c = q.dequeue()
    q ++= List(1, 2, 3, 4)
    val d = q.dequeue()
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
    }
    flow { qs << ("Hello", "World!", "Test") }
    flow { c << qi }
    assert(a.get === 5)
    assert(b.get === "World!")
    assert(c.get === 4)
  }

  @Test
  def concurrentStressTest {
    val q = PromiseStream[Int]()
    Future((0 until 50000) foreach (v ⇒ Future(q enqueue v)))
    val future = Future.sequence(List.fill(10)(Future(Future.sequence(List.fill(10000)(q.dequeue()))).flatMap(x ⇒ x))) map (_.flatten.sorted)
    Future((50000 until 100000) foreach (v ⇒ Future(q enqueue v)))
    val result = future.get
    assert(result === List.range(0, 100000), "Result did not match 'List.range(0, 100000)'")
  }
}
