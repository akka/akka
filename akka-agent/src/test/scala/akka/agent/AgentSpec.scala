package akka.agent

import akka.dispatch.{ Await, Future }
import akka.util.{ Duration, Timeout }
import akka.util.duration._
import akka.testkit._
import scala.concurrent.stm._
import java.util.concurrent.{ CountDownLatch, TimeUnit }

class CountDownFunction[A](num: Int = 1) extends Function1[A, A] {
  val latch = new CountDownLatch(num)
  def apply(a: A) = { latch.countDown(); a }
  def await(timeout: Duration) = latch.await(timeout.length, timeout.unit)
}

class AgentSpec extends AkkaSpec {

  implicit val timeout = Timeout(5.seconds.dilated)

  "Agent" must {
    "update with send dispatches in order sent" in {
      val countDown = new CountDownFunction[String]

      val agent = Agent("a")
      agent send (_ + "b")
      agent send (_ + "c")
      agent send (_ + "d")
      agent send countDown

      countDown.await(5 seconds)
      agent() must be("abcd")

      agent.close()
    }

    "maintain order between send and sendOff" in {
      val countDown = new CountDownFunction[String]
      val l1, l2 = new CountDownLatch(1)

      val agent = Agent("a")
      agent send (_ + "b")
      val longRunning = (s: String) ⇒ { l1.countDown; l2.await(5, TimeUnit.SECONDS); s + "c" }
      agent sendOff longRunning
      agent send (_ + "d")
      agent send countDown
      l1.await(5, TimeUnit.SECONDS)
      l2.countDown
      countDown.await(5 seconds)
      agent() must be("abcd")

      agent.close()
    }

    "maintain order between alter and alterOff" in {
      val l1, l2 = new CountDownLatch(1)
      val agent = Agent("a")

      val r1 = agent.alter(_ + "b")(5000)
      val r2 = agent.alterOff((s: String) ⇒ { l1.countDown; l2.await(5, TimeUnit.SECONDS); s + "c" })(5000)
      val r3 = agent.alter(_ + "d")(5000)
      val result = Future.sequence(Seq(r1, r2, r3)).map(_.mkString(":"))
      l1.await(5, TimeUnit.SECONDS)
      l2.countDown

      Await.result(result, 5 seconds) must be === "ab:abc:abcd"

      agent() must be("abcd")

      agent.close()
    }

    "be immediately readable" in {
      val countDown = new CountDownFunction[Int]
      val readLatch = new CountDownLatch(1)
      val readTimeout = 5 seconds

      val agent = Agent(5)
      val f1 = (i: Int) ⇒ {
        readLatch.await(readTimeout.length, readTimeout.unit)
        i + 5
      }
      agent send f1
      val read = agent()
      readLatch.countDown()
      agent send countDown

      countDown.await(5 seconds)
      read must be(5)
      agent() must be(10)

      agent.close()
    }

    "be readable within a transaction" in {
      val agent = Agent(5)
      val value = atomic { t ⇒ agent() }
      value must be(5)
      agent.close()
    }

    "dispatch sends in successful transactions" in {
      val countDown = new CountDownFunction[Int]

      val agent = Agent(5)
      atomic { t ⇒
        agent send (_ * 2)
      }
      agent send countDown

      countDown.await(5 seconds)
      agent() must be(10)

      agent.close()
    }

    "not dispatch sends in aborted transactions" in {
      val countDown = new CountDownFunction[Int]

      val agent = Agent(5)

      try {
        atomic { t ⇒
          agent send (_ * 2)
          throw new RuntimeException("Expected failure")
        }
      } catch { case _ ⇒ }

      agent send countDown

      countDown.await(5 seconds)
      agent() must be(5)

      agent.close()
    }

    "be able to return a 'queued' future" in {
      val agent = Agent("a")
      agent send (_ + "b")
      agent send (_ + "c")

      Await.result(agent.future, timeout.duration) must be("abc")

      agent.close()
    }

    "be able to await the value after updates have completed" in {
      val agent = Agent("a")
      agent send (_ + "b")
      agent send (_ + "c")

      agent.await must be("abc")

      agent.close()
    }

    "be able to be mapped" in {
      val agent1 = Agent(5)
      val agent2 = agent1 map (_ * 2)

      agent1() must be(5)
      agent2() must be(10)

      agent1.close()
      agent2.close()
    }

    "be able to be used in a 'foreach' for comprehension" in {
      val agent = Agent(3)
      var result = 0

      for (value ← agent) {
        result += value
      }

      result must be(3)

      agent.close()
    }

    "be able to be used in a 'map' for comprehension" in {
      val agent1 = Agent(5)
      val agent2 = for (value ← agent1) yield value * 2

      agent1() must be(5)
      agent2() must be(10)

      agent1.close()
      agent2.close()
    }

    "be able to be used in a 'flatMap' for comprehension" in {
      val agent1 = Agent(1)
      val agent2 = Agent(2)

      val agent3 = for {
        value1 ← agent1
        value2 ← agent2
      } yield value1 + value2

      agent1() must be(1)
      agent2() must be(2)
      agent3() must be(3)

      agent1.close()
      agent2.close()
      agent3.close()
    }
  }
}

