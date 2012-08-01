/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.routing

import language.postfixOps

import java.util.concurrent.atomic.AtomicInteger
import org.junit.runner.RunWith
import akka.actor.{ Props, Deploy, Actor, ActorRef }
import akka.ConfigurationException
import scala.concurrent.Await
import akka.pattern.{ ask, gracefulStop }
import akka.testkit.{ TestLatch, ImplicitSender, DefaultTimeout, AkkaSpec }
import scala.concurrent.util.duration.intToDurationInt
import akka.actor.UnstartedCell

object ConfiguredLocalRoutingSpec {
  val config = """
    akka {
      actor {
        default-dispatcher {
          executor = "thread-pool-executor"
          thread-pool-executor {
            core-pool-size-min = 8
            core-pool-size-max = 16
          }
        }
        deployment {
          /config {
            router = random
            nr-of-instances = 4
          }
          /weird {
            router = round-robin
            nr-of-instances = 3
          }
          "/weird/*" {
            router = round-robin
            nr-of-instances = 2
          }
        }
      }
    }
  """
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ConfiguredLocalRoutingSpec extends AkkaSpec(ConfiguredLocalRoutingSpec.config) with DefaultTimeout with ImplicitSender {

  def routerConfig(ref: ActorRef): RouterConfig = ref match {
    case r: RoutedActorRef ⇒
      r.underlying match {
        case c: RoutedActorCell ⇒ c.routerConfig
        case _: UnstartedCell   ⇒ awaitCond(r.isStarted, 1 second, 10 millis); routerConfig(ref)
      }
  }

  "RouterConfig" must {

    "be picked up from Props" in {
      val actor = system.actorOf(Props(new Actor {
        def receive = {
          case "get" ⇒ sender ! context.props
        }
      }).withRouter(RoundRobinRouter(12)), "someOther")
      routerConfig(actor) must be === RoundRobinRouter(12)
      Await.result(gracefulStop(actor, 3 seconds), 3 seconds)
    }

    "be overridable in config" in {
      val actor = system.actorOf(Props(new Actor {
        def receive = {
          case "get" ⇒ sender ! context.props
        }
      }).withRouter(RoundRobinRouter(12)), "config")
      routerConfig(actor) must be === RandomRouter(4)
      Await.result(gracefulStop(actor, 3 seconds), 3 seconds)
    }

    "be overridable in explicit deployment" in {
      val actor = system.actorOf(Props(new Actor {
        def receive = {
          case "get" ⇒ sender ! context.props
        }
      }).withRouter(FromConfig).withDeploy(Deploy(routerConfig = RoundRobinRouter(12))), "someOther")
      routerConfig(actor) must be === RoundRobinRouter(12)
      Await.result(gracefulStop(actor, 3 seconds), 3 seconds)
    }

    "be overridable in config even with explicit deployment" in {
      val actor = system.actorOf(Props(new Actor {
        def receive = {
          case "get" ⇒ sender ! context.props
        }
      }).withRouter(FromConfig).withDeploy(Deploy(routerConfig = RoundRobinRouter(12))), "config")
      routerConfig(actor) must be === RandomRouter(4)
      Await.result(gracefulStop(actor, 3 seconds), 3 seconds)
    }

    "fail with an exception if not correct" in {
      intercept[ConfigurationException] {
        system.actorOf(Props.empty.withRouter(FromConfig))
      }
    }

    "not get confused when trying to wildcard-configure children" in {
      val router = system.actorOf(Props(new Actor {
        testActor ! self
        def receive = { case _ ⇒ }
      }).withRouter(FromConfig), "weird")
      val recv = Set() ++ (for (_ ← 1 to 3) yield expectMsgType[ActorRef])
      val expc = Set('a', 'b', 'c') map (i ⇒ system.actorFor("/user/weird/$" + i))
      recv must be(expc)
      expectNoMsg(1 second)
    }

  }

  "round robin router" must {

    "be able to shut down its instance" in {
      val helloLatch = new TestLatch(5)
      val stopLatch = new TestLatch(5)

      val actor = system.actorOf(Props(new Actor {
        def receive = {
          case "hello" ⇒ helloLatch.countDown()
        }

        override def postStop() {
          stopLatch.countDown()
        }
      }).withRouter(RoundRobinRouter(5)), "round-robin-shutdown")

      actor ! "hello"
      actor ! "hello"
      actor ! "hello"
      actor ! "hello"
      actor ! "hello"
      Await.ready(helloLatch, 5 seconds)

      system.stop(actor)
      Await.ready(stopLatch, 5 seconds)
    }

    "deliver messages in a round robin fashion" in {
      val connectionCount = 10
      val iterationCount = 10
      val doneLatch = new TestLatch(connectionCount)

      val counter = new AtomicInteger
      var replies = Map.empty[Int, Int]
      for (i ← 0 until connectionCount) {
        replies += i -> 0
      }

      val actor = system.actorOf(Props(new Actor {
        lazy val id = counter.getAndIncrement()
        def receive = {
          case "hit" ⇒ sender ! id
          case "end" ⇒ doneLatch.countDown()
        }
      }).withRouter(RoundRobinRouter(connectionCount)), "round-robin")

      for (i ← 0 until iterationCount) {
        for (k ← 0 until connectionCount) {
          val id = Await.result((actor ? "hit").mapTo[Int], timeout.duration)
          replies = replies + (id -> (replies(id) + 1))
        }
      }

      counter.get must be(connectionCount)

      actor ! Broadcast("end")
      Await.ready(doneLatch, 5 seconds)

      replies.values foreach { _ must be(iterationCount) }
    }

    "deliver a broadcast message using the !" in {
      val helloLatch = new TestLatch(5)
      val stopLatch = new TestLatch(5)

      val actor = system.actorOf(Props(new Actor {
        def receive = {
          case "hello" ⇒ helloLatch.countDown()
        }

        override def postStop() {
          stopLatch.countDown()
        }
      }).withRouter(RoundRobinRouter(5)), "round-robin-broadcast")

      actor ! Broadcast("hello")
      Await.ready(helloLatch, 5 seconds)

      system.stop(actor)
      Await.ready(stopLatch, 5 seconds)
    }
  }

  "random router" must {

    "be able to shut down its instance" in {
      val stopLatch = new TestLatch(7)

      val actor = system.actorOf(Props(new Actor {
        def receive = {
          case "hello" ⇒ sender ! "world"
        }

        override def postStop() {
          stopLatch.countDown()
        }
      }).withRouter(RandomRouter(7)), "random-shutdown")

      actor ! "hello"
      actor ! "hello"
      actor ! "hello"
      actor ! "hello"
      actor ! "hello"

      within(2 seconds) {
        for (i ← 1 to 5) expectMsg("world")
      }

      system.stop(actor)
      Await.ready(stopLatch, 5 seconds)
    }

    "deliver messages in a random fashion" in {
      val connectionCount = 10
      val iterationCount = 100
      val doneLatch = new TestLatch(connectionCount)

      val counter = new AtomicInteger
      var replies = Map.empty[Int, Int]
      for (i ← 0 until connectionCount) {
        replies = replies + (i -> 0)
      }

      val actor = system.actorOf(Props(new Actor {
        lazy val id = counter.getAndIncrement()
        def receive = {
          case "hit" ⇒ sender ! id
          case "end" ⇒ doneLatch.countDown()
        }
      }).withRouter(RandomRouter(connectionCount)), "random")

      for (i ← 0 until iterationCount) {
        for (k ← 0 until connectionCount) {
          val id = Await.result((actor ? "hit").mapTo[Int], timeout.duration)
          replies = replies + (id -> (replies(id) + 1))
        }
      }

      counter.get must be(connectionCount)

      actor ! Broadcast("end")
      Await.ready(doneLatch, 5 seconds)

      replies.values foreach { _ must be > (0) }
      replies.values.sum must be === iterationCount * connectionCount
    }

    "deliver a broadcast message using the !" in {
      val helloLatch = new TestLatch(6)
      val stopLatch = new TestLatch(6)

      val actor = system.actorOf(Props(new Actor {
        def receive = {
          case "hello" ⇒ helloLatch.countDown()
        }

        override def postStop() {
          stopLatch.countDown()
        }
      }).withRouter(RandomRouter(6)), "random-broadcast")

      actor ! Broadcast("hello")
      Await.ready(helloLatch, 5 seconds)

      system.stop(actor)
      Await.ready(stopLatch, 5 seconds)
    }
  }
}
