/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.agent

import language.postfixOps

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.util.control.NonFatal
import akka.util.Timeout
import akka.testkit._
import scala.concurrent.stm._
import java.util.concurrent.{ CountDownLatch }

class CountDownFunction[A](num: Int = 1) extends Function1[A, A] {
  val latch = new CountDownLatch(num)
  def apply(a: A) = { latch.countDown(); a }
  def await(timeout: Duration) = latch.await(timeout.length, timeout.unit)
}

class AgentSpec extends AkkaSpec {

  implicit val timeout = Timeout(5.seconds.dilated)
  import system.dispatcher
  "Agent" must {
    "update with send dispatches in order sent" in {
      val countDown = new CountDownFunction[String]

      val agent = Agent("a")
      agent send (_ + "b")
      agent send (_ + "c")
      agent send (_ + "d")
      agent send countDown

      countDown.await(5 seconds)
      agent() should ===("abcd")
    }

    "maintain order between send and sendOff" in {
      val countDown = new CountDownFunction[String]
      val l1, l2 = new TestLatch(1)
      val agent = Agent("a")
      agent send (_ + "b")
      agent.sendOff((s: String) ⇒ { l1.countDown; Await.ready(l2, timeout.duration); s + "c" })
      Await.ready(l1, timeout.duration)
      agent send (_ + "d")
      agent send countDown
      l2.countDown
      countDown.await(5 seconds)
      agent() should ===("abcd")
    }

    "maintain order between alter and alterOff" in {
      val l1, l2 = new TestLatch(1)
      val agent = Agent("a")

      val r1 = agent.alter(_ + "b")
      val r2 = agent.alterOff(s ⇒ { l1.countDown; Await.ready(l2, timeout.duration); s + "c" })
      Await.ready(l1, timeout.duration)
      val r3 = agent.alter(_ + "d")
      val result = Future.sequence(Seq(r1, r2, r3)).map(_.mkString(":"))
      l2.countDown

      Await.result(result, 5 seconds) should ===("ab:abc:abcd")

      agent() should ===("abcd")
    }

    "be immediately readable" in {
      val countDown = new CountDownFunction[Int]
      val readLatch = new TestLatch(1)
      val readTimeout = 5 seconds

      val agent = Agent(5)
      val f1 = (i: Int) ⇒ {
        Await.ready(readLatch, readTimeout)
        i + 5
      }
      agent send f1
      val read = agent()
      readLatch.countDown()
      agent send countDown

      countDown.await(5 seconds)
      read should ===(5)
      agent() should ===(10)
    }

    "be readable within a transaction" in {
      val agent = Agent(5)
      val value = atomic { t ⇒ agent() }
      value should ===(5)
    }

    "dispatch sends in successful transactions" in {
      val countDown = new CountDownFunction[Int]

      val agent = Agent(5)
      atomic { t ⇒
        agent send (_ * 2)
      }
      agent send countDown

      countDown.await(5 seconds)
      agent() should ===(10)
    }

    "not dispatch sends in aborted transactions" in {
      val countDown = new CountDownFunction[Int]

      val agent = Agent(5)

      try {
        atomic { t ⇒
          agent send (_ * 2)
          throw new RuntimeException("Expected failure")
        }
      } catch { case NonFatal(_) ⇒ }

      agent send countDown

      countDown.await(5 seconds)
      agent() should ===(5)
    }

    "be able to return a 'queued' future" in {
      val agent = Agent("a")
      agent send (_ + "b")
      agent send (_ + "c")

      Await.result(agent.future, timeout.duration) should ===("abc")
    }

    "be able to await the value after updates have completed" in {
      val agent = Agent("a")
      agent send (_ + "b")
      agent send (_ + "c")

      Await.result(agent.future, timeout.duration) should ===("abc")
    }

    "be able to be mapped" in {
      val agent1 = Agent(5)
      val agent2 = agent1 map (_ * 2)

      agent1() should ===(5)
      agent2() should ===(10)
    }

    "be able to be used in a 'foreach' for comprehension" in {
      val agent = Agent(3)
      var result = 0

      for (value ← agent) {
        result += value
      }

      result should ===(3)
    }

    "be able to be used in a 'map' for comprehension" in {
      val agent1 = Agent(5)
      val agent2 = for (value ← agent1) yield value * 2

      agent1() should ===(5)
      agent2() should ===(10)
    }

    "be able to be used in a 'flatMap' for comprehension" in {
      val agent1 = Agent(1)
      val agent2 = Agent(2)

      val agent3 = for {
        value1 ← agent1
        value2 ← agent2
      } yield value1 + value2

      agent1() should ===(1)
      agent2() should ===(2)
      agent3() should ===(3)
    }
  }
}

