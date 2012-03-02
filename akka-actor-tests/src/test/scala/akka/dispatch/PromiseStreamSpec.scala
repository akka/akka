package akka.dispatch

import Future.flow
import akka.util.cps._
import akka.util.Timeout
import akka.util.duration._
import akka.testkit.AkkaSpec
import akka.testkit.DefaultTimeout

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class PromiseStreamSpec extends AkkaSpec with DefaultTimeout {

  "A PromiseStream" must {

    "work" in {
      val a, b, c = Promise[Int]()
      val q = PromiseStream[Int]()
      flow { q << (1, 2, 3) }
      flow {
        a << q()
        b << q
        c << q()
      }
      assert(Await.result(a, timeout.duration) === 1)
      assert(Await.result(b, timeout.duration) === 2)
      assert(Await.result(c, timeout.duration) === 3)
    }

    "pend" in {
      val a, b, c = Promise[Int]()
      val q = PromiseStream[Int]()
      flow {
        a << q
        b << q()
        c << q
      }
      flow { q <<< List(1, 2, 3) }
      assert(Await.result(a, timeout.duration) === 1)
      assert(Await.result(b, timeout.duration) === 2)
      assert(Await.result(c, timeout.duration) === 3)
    }

    "pend again" in {
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
      assert(Await.result(a, timeout.duration) === 1)
      assert(Await.result(b, timeout.duration) === 2)
      assert(Await.result(c, timeout.duration) === 3)
      assert(Await.result(d, timeout.duration) === 4)
    }

    "enque" in {
      val q = PromiseStream[Int]()
      val a = q.dequeue()
      val b = q.dequeue()
      val c, d = Promise[Int]()
      flow {
        c << q
        d << q
      }
      q ++= List(1, 2, 3, 4)

      assert(Await.result(a, timeout.duration) === 1)
      assert(Await.result(b, timeout.duration) === 2)
      assert(Await.result(c, timeout.duration) === 3)
      assert(Await.result(d, timeout.duration) === 4)
    }

    "map" in {
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
      assert(Await.result(a, timeout.duration) === 5)
      assert(Await.result(b, timeout.duration) === "World!")
      assert(Await.result(c, timeout.duration) === 4)
    }

    "map futures" in {
      val q = PromiseStream[String]()
      flow {
        q << (Future("a"), Future("b"), Future("c"))
      }
      val a, b, c = q.dequeue
      Await.result(a, timeout.duration) must be("a")
      Await.result(b, timeout.duration) must be("b")
      Await.result(c, timeout.duration) must be("c")
    }

    "not fail under concurrent stress" in {
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

      assert(Await.result(future, timeout.duration) === (1L to 100000L).sum)
    }
  }
}
