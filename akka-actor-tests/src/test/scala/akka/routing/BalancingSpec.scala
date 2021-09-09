/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.routing

import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{ Await, Future, Promise }
import scala.concurrent.duration._
import akka.actor.ActorRef
import akka.actor.Actor
import akka.actor.Props
import akka.testkit.AkkaSpec
import akka.testkit.GHExcludeTest
import akka.testkit.ImplicitSender
import akka.testkit.TestLatch
import org.scalatest.BeforeAndAfterEach

object BalancingSpec {
  val counter = new AtomicInteger(1)

  class Worker(latch: TestLatch, startOthers: Future[Unit]) extends Actor {
    lazy val id = counter.getAndIncrement()

    def receive = {
      case _: Int =>
        latch.countDown()
        latch
        if (id == 1) {
          // wait for all routees to receive a message before processing
          Await.result(latch, 1.minute)
        } else {
          // wait for the first worker to process messages before also processing
          Await.result(startOthers, 1.minute)
        }
        sender() ! id
    }
  }

  class Parent extends Actor {
    val pool =
      context.actorOf(BalancingPool(2).props(routeeProps = Props(classOf[Worker], TestLatch(0)(context.system), Future.successful(()))))

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

  def test(pool: ActorRef, startOthers: Promise[Unit]): Unit = {
    val iterationCount = 100

    for (i <- 1 to iterationCount) {
      pool ! i
    }

    // all but one worker are blocked
    val replies1 = receiveN(iterationCount - poolSize + 1)
    // all replies from the unblocked worker so far
    replies1.toSet should be(Set(1))
    log.warning(lastSender.toString)
    expectNoMessage(1.second)

    // Now unblock the other workers from also making progress
    startOthers.success(())
    val replies2 = receiveN(poolSize - 1)
    // the remaining replies come from the blocked
    replies2.toSet should be((2 to poolSize).toSet)
    expectNoMessage(500.millis)

  }

  "balancing pool" must {

    "deliver messages in a balancing fashion when defined programatically" in {
      val latch = TestLatch(poolSize)
      val startOthers = Promise[Unit]()
      val pool = system.actorOf(
        BalancingPool(poolSize).props(routeeProps = Props(classOf[Worker], latch, startOthers.future)),
        name = "balancingPool-1")
      test(pool, startOthers)
    }

    "deliver messages in a balancing fashion when defined in config" taggedAs GHExcludeTest in {
      val latch = TestLatch(poolSize)
      val startOthers = Promise[Unit]()
      val pool =
        system.actorOf(FromConfig().props(routeeProps = Props(classOf[Worker], latch, startOthers.future)), name = "balancingPool-2")
      test(pool, startOthers)
    }

    "deliver messages in a balancing fashion when overridden in config" taggedAs GHExcludeTest in {
      val latch = TestLatch(poolSize)
      val startOthers = Promise[Unit]()
      val pool =
        system.actorOf(BalancingPool(1).props(routeeProps = Props(classOf[Worker], latch, startOthers.future)), name = "balancingPool-3")
      test(pool, startOthers)
    }

    "work with anonymous actor names" in {
      // the dispatcher-id must not contain invalid config key characters (e.g. $a)
      system.actorOf(Props[Parent]()) ! 1000
      expectMsgType[Int]
    }

    "work with encoded actor names" in {
      val encName = URLEncoder.encode("abcå6#$€xyz", "utf-8")
      // % is a valid config key character (e.g. %C3%A5)
      system.actorOf(Props[Parent](), encName) ! 1001
      expectMsgType[Int]
    }

  }
}
