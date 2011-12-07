package akka.routing

import akka.actor._
import akka.routing._
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ CountDownLatch, TimeUnit }
import akka.testkit.AkkaSpec
import akka.actor.DeploymentConfig._
import akka.routing.Routing.Broadcast
import akka.testkit.DefaultTimeout

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ConfiguredLocalRoutingSpec extends AkkaSpec with DefaultTimeout {

  val deployer = system.asInstanceOf[ActorSystemImpl].provider.deployer

  "round robin router" must {

    "be able to shut down its instance" in {
      val path = system / "round-robin-0"

      deployer.deploy(
        Deploy(
          path.toString,
          None,
          RoundRobin,
          NrOfInstances(5),
          LocalScope))

      val helloLatch = new CountDownLatch(5)
      val stopLatch = new CountDownLatch(5)

      val actor = system.actorOf(Props(new Actor {
        def receive = {
          case "hello" ⇒ helloLatch.countDown()
        }

        override def postStop() {
          stopLatch.countDown()
        }
      }), path.name)

      actor ! "hello"
      actor ! "hello"
      actor ! "hello"
      actor ! "hello"
      actor ! "hello"
      helloLatch.await(5, TimeUnit.SECONDS) must be(true)

      actor.stop()
      stopLatch.await(5, TimeUnit.SECONDS) must be(true)
    }

    "deliver messages in a round robin fashion" in {
      val path = system / "round-robin-1"

      deployer.deploy(
        Deploy(
          path.toString,
          None,
          RoundRobin,
          NrOfInstances(10),
          LocalScope))

      val connectionCount = 10
      val iterationCount = 10
      val doneLatch = new CountDownLatch(connectionCount)

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
      }), path.name)

      for (i ← 0 until iterationCount) {
        for (k ← 0 until connectionCount) {
          val id = (actor ? "hit").as[Int].getOrElse(fail("No id returned by actor"))
          replies = replies + (id -> (replies(id) + 1))
        }
      }

      counter.get must be(connectionCount)

      actor ! Broadcast("end")
      doneLatch.await(5, TimeUnit.SECONDS) must be(true)

      replies.values foreach { _ must be(10) }
    }

    "deliver a broadcast message using the !" in {
      val path = system / "round-robin-2"

      deployer.deploy(
        Deploy(
          path.toString,
          None,
          RoundRobin,
          NrOfInstances(5),
          LocalScope))

      val helloLatch = new CountDownLatch(5)
      val stopLatch = new CountDownLatch(5)

      val actor = system.actorOf(Props(new Actor {
        def receive = {
          case "hello" ⇒ helloLatch.countDown()
        }

        override def postStop() {
          stopLatch.countDown()
        }
      }), path.name)

      actor ! Broadcast("hello")
      helloLatch.await(5, TimeUnit.SECONDS) must be(true)

      actor.stop()
      stopLatch.await(5, TimeUnit.SECONDS) must be(true)
    }
  }

  "random router" must {

    "be able to shut down its instance" in {
      val path = system / "random-0"

      deployer.deploy(
        Deploy(
          path.toString,
          None,
          Random,
          NrOfInstances(7),
          LocalScope))

      val stopLatch = new CountDownLatch(7)

      val actor = system.actorOf(Props(new Actor {
        def receive = {
          case "hello" ⇒ {}
        }

        override def postStop() {
          stopLatch.countDown()
        }
      }), path.name)

      actor ! "hello"
      actor ! "hello"
      actor ! "hello"
      actor ! "hello"
      actor ! "hello"

      actor.stop()
      stopLatch.await(5, TimeUnit.SECONDS) must be(true)
    }

    "deliver messages in a random fashion" in {
      val path = system / "random-1"

      deployer.deploy(
        Deploy(
          path.toString,
          None,
          Random,
          NrOfInstances(10),
          LocalScope))

      val connectionCount = 10
      val iterationCount = 10
      val doneLatch = new CountDownLatch(connectionCount)

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
      }), path.name)

      for (i ← 0 until iterationCount) {
        for (k ← 0 until connectionCount) {
          val id = (actor ? "hit").as[Int].getOrElse(fail("No id returned by actor"))
          replies = replies + (id -> (replies(id) + 1))
        }
      }

      counter.get must be(connectionCount)

      actor ! Broadcast("end")
      doneLatch.await(5, TimeUnit.SECONDS) must be(true)

      replies.values foreach { _ must be > (0) }
    }

    "deliver a broadcast message using the !" in {
      val path = system / "random-2"

      deployer.deploy(
        Deploy(
          path.toString,
          None,
          Random,
          NrOfInstances(6),
          LocalScope))

      val helloLatch = new CountDownLatch(6)
      val stopLatch = new CountDownLatch(6)

      val actor = system.actorOf(Props(new Actor {
        def receive = {
          case "hello" ⇒ helloLatch.countDown()
        }

        override def postStop() {
          stopLatch.countDown()
        }
      }), path.name)

      actor ! Broadcast("hello")
      helloLatch.await(5, TimeUnit.SECONDS) must be(true)

      actor.stop()
      stopLatch.await(5, TimeUnit.SECONDS) must be(true)
    }
  }
}
