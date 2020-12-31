/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.dispatch

import java.util.concurrent.{ CountDownLatch, TimeUnit }

import scala.concurrent.Await

import org.scalatest.BeforeAndAfterEach

import akka.actor._
import akka.pattern.ask
import akka.testkit._
import akka.testkit.AkkaSpec

object PinnedActorSpec {
  val config = """
    pinned-dispatcher {
      executor = thread-pool-executor
      type = PinnedDispatcher
    }
    """

  class TestActor extends Actor {
    def receive = {
      case "Hello"   => sender() ! "World"
      case "Failure" => throw new RuntimeException("Expected exception; to test fault-tolerance")
    }
  }
}

class PinnedActorSpec extends AkkaSpec(PinnedActorSpec.config) with BeforeAndAfterEach with DefaultTimeout {

  // TODO DOTTY
  protected override def runTest(testName: String, args: org.scalatest.Args): org.scalatest.Status = akka.testkit.ScalatestRunTest.scalatestRunTest(testName, args)

  import PinnedActorSpec._

  "A PinnedActor" must {

    "support tell" in {
      val oneWay = new CountDownLatch(1)
      val actor = system.actorOf(
        Props(new Actor { def receive = { case "OneWay" => oneWay.countDown() } }).withDispatcher("pinned-dispatcher"))
      actor ! "OneWay"
      require(oneWay.await(1, TimeUnit.SECONDS))
      system.stop(actor)
    }

    "support ask/reply" in {
      val actor = system.actorOf(Props[TestActor]().withDispatcher("pinned-dispatcher"))
      require("World" === Await.result(actor ? "Hello", timeout.duration))
      system.stop(actor)
    }
  }
}
