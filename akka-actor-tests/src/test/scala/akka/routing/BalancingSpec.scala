/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.routing

import language.postfixOps
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Await
import scala.concurrent.duration._
import akka.actor.{ Props, Actor }
import akka.testkit.{ TestLatch, ImplicitSender, AkkaSpec }
import akka.actor.ActorRef
import org.scalatest.BeforeAndAfterEach

object BalancingSpec {
  val counter = new AtomicInteger(1)

  class Worker(latch: TestLatch) extends Actor {
    lazy val id = counter.getAndIncrement()
    def receive = {
      case msg ⇒
        if (id == 1) Thread.sleep(10) // dispatch to other routees
        else Await.ready(latch, 1.minute)
        sender ! id
    }
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class BalancingSpec extends AkkaSpec(
  """
    akka.actor.deployment {
      /balancingPool-2 {
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
    val iterationCount = 100

    for (i ← 1 to iterationCount) {
      pool ! "hit-" + i
    }

    // all but one worker are blocked
    val replies1 = receiveN(iterationCount - poolSize + 1)
    expectNoMsg(1.second)
    // all replies from the unblocked worker so far
    replies1.toSet should be(Set(1))

    latch.countDown()
    val replies2 = receiveN(poolSize - 1)
    // the remaining replies come from the blocked 
    replies2.toSet should be((2 to poolSize).toSet)
    expectNoMsg(500.millis)

  }

  "balancing pool" must {

    "deliver messages in a balancing fashion when defined programatically" in {
      val latch = TestLatch(1)
      val pool = system.actorOf(BalancingPool(poolSize).props(routeeProps =
        Props(classOf[Worker], latch)), name = "balancingPool-1")
      test(pool, latch)
    }

    "deliver messages in a balancing fashion when defined in config" in {
      val latch = TestLatch(1)
      val pool = system.actorOf(BalancingPool(1).props(routeeProps =
        Props(classOf[Worker], latch)), name = "balancingPool-2")
      test(pool, latch)
    }

  }
}
