/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.routing

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Await
import scala.concurrent.duration._
import akka.actor.{ Actor, Props }
import akka.testkit.{ AkkaSpec, ImplicitSender, TestLatch }
import akka.actor.ActorRef
import org.scalatest.BeforeAndAfterEach
import java.net.URLEncoder

object BalancingSpec {
  val counter = new AtomicInteger(1)

  class Worker(latch: TestLatch) extends Actor {
    lazy val id = counter.getAndIncrement()

    override def preStart(): Unit = latch.countDown()

    def receive = {
      case msg: Int =>
        if (id != 1)
          Await.ready(latch, 1.minute)
        else if (msg <= 10)
          Thread.sleep(50) // dispatch to other routees
        sender() ! id
    }
  }

  class Parent extends Actor {
    val pool =
      context.actorOf(BalancingPool(2).props(routeeProps = Props(classOf[Worker], TestLatch(0)(context.system))))

    def receive = {
      case msg => pool.forward(msg)
    }
  }
}

class BalancingSpec extends AkkaSpec("""
    akka.actor.deployment {
      /balancingPool-2 {
        router = balancing-pool
        nr-of-instances = 5
        pool-dispatcher {
          attempt-teamwork = on
        }
      }
      /balancingPool-3 {
        router = balancing-pool
        nr-of-instances = 5
        pool-dispatcher {
          attempt-teamwork = on
        }
      }
    }
    """) with ImplicitSender with BeforeAndAfterEach {
  import BalancingSpec._

  val poolSize = 5 // must be less than fork-join parallelism-min, which is 8 in AkkaSpec

  override def beforeEach(): Unit = {
    counter.set(1)
  }

  def test(pool: ActorRef, latch: TestLatch): Unit = {
    // wait until all routees have started
    Await.ready(latch, remainingOrDefault)

    latch.reset()
    val iterationCount = 100

    for (i <- 1 to iterationCount) {
      pool ! i
    }

    // all but one worker are blocked
    val replies1 = receiveN(iterationCount - poolSize + 1)
    expectNoMsg(1.second)
    // all replies from the unblocked worker so far
    replies1.toSet should be(Set(1))

    latch.open()
    val replies2 = receiveN(poolSize - 1)
    // the remaining replies come from the blocked
    replies2.toSet should be((2 to poolSize).toSet)
    expectNoMsg(500.millis)

  }

  "balancing pool" must {

    "deliver messages in a balancing fashion when defined programatically" in {
      val latch = TestLatch(poolSize)
      val pool = system.actorOf(
        BalancingPool(poolSize).props(routeeProps = Props(classOf[Worker], latch)),
        name = "balancingPool-1")
      test(pool, latch)
    }

    "deliver messages in a balancing fashion when defined in config" in {
      val latch = TestLatch(poolSize)
      val pool =
        system.actorOf(FromConfig().props(routeeProps = Props(classOf[Worker], latch)), name = "balancingPool-2")
      test(pool, latch)
    }

    "deliver messages in a balancing fashion when overridden in config" in {
      val latch = TestLatch(poolSize)
      val pool =
        system.actorOf(BalancingPool(1).props(routeeProps = Props(classOf[Worker], latch)), name = "balancingPool-3")
      test(pool, latch)
    }

    "work with anonymous actor names" in {
      // the dispatcher-id must not contain invalid config key characters (e.g. $a)
      system.actorOf(Props[Parent]) ! 1000
      expectMsgType[Int]
    }

    "work with encoded actor names" in {
      val encName = URLEncoder.encode("abcå6#$€xyz", "utf-8")
      // % is a valid config key character (e.g. %C3%A5)
      system.actorOf(Props[Parent], encName) ! 1001
      expectMsgType[Int]
    }

  }
}
