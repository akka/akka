package akka.routing

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

import akka.actor._
import Actor._
import DeploymentConfig._
import akka.routing._
import Routing.Broadcast

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ CountDownLatch, TimeUnit }

class ConfiguredLocalRoutingSpec extends WordSpec with MustMatchers {

  "round robin router" must {

    "be able to shut down its instance" in {
      val address = "round-robin-0"

      Deployer.deploy(
        Deploy(
          address,
          None,
          RoundRobin,
          NrOfInstances(5),
          NoOpFailureDetector,
          LocalScope))

      val helloLatch = new CountDownLatch(5)
      val stopLatch = new CountDownLatch(5)

      val actor = actorOf(new Actor {
        def receive = {
          case "hello" ⇒ helloLatch.countDown()
        }

        override def postStop() {
          stopLatch.countDown()
        }
      }, address)

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
      val address = "round-robin-1"

      Deployer.deploy(
        Deploy(
          address,
          None,
          RoundRobin,
          NrOfInstances(10),
          NoOpFailureDetector,
          LocalScope))

      val connectionCount = 10
      val iterationCount = 10
      val doneLatch = new CountDownLatch(connectionCount)

      val counter = new AtomicInteger
      var replies = Map.empty[Int, Int]
      for (i ← 0 until connectionCount) {
        replies = replies + (i -> 0)
      }

      val actor = actorOf(new Actor {
        lazy val id = counter.getAndIncrement()
        def receive = {
          case "hit" ⇒ reply(id)
          case "end" ⇒ doneLatch.countDown()
        }
      }, address)

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
      val address = "round-robin-2"

      Deployer.deploy(
        Deploy(
          address,
          None,
          RoundRobin,
          NrOfInstances(5),
          NoOpFailureDetector,
          LocalScope))

      val helloLatch = new CountDownLatch(5)
      val stopLatch = new CountDownLatch(5)

      val actor = actorOf(new Actor {
        def receive = {
          case "hello" ⇒ helloLatch.countDown()
        }

        override def postStop() {
          stopLatch.countDown()
        }
      }, address)

      actor ! Broadcast("hello")
      helloLatch.await(5, TimeUnit.SECONDS) must be(true)

      actor.stop()
      stopLatch.await(5, TimeUnit.SECONDS) must be(true)
    }
  }

  "random router" must {

    "be able to shut down its instance" in {
      val address = "random-0"

      Deployer.deploy(
        Deploy(
          address,
          None,
          Random,
          NrOfInstances(7),
          NoOpFailureDetector,
          LocalScope))

      val stopLatch = new CountDownLatch(7)

      val actor = actorOf(new Actor {
        def receive = {
          case "hello" ⇒ {}
        }

        override def postStop() {
          stopLatch.countDown()
        }
      }, address)

      actor ! "hello"
      actor ! "hello"
      actor ! "hello"
      actor ! "hello"
      actor ! "hello"

      actor.stop()
      stopLatch.await(5, TimeUnit.SECONDS) must be(true)
    }

    "deliver messages in a random fashion" in {
      val address = "random-1"

      Deployer.deploy(
        Deploy(
          address,
          None,
          Random,
          NrOfInstances(10),
          NoOpFailureDetector,
          LocalScope))

      val connectionCount = 10
      val iterationCount = 10
      val doneLatch = new CountDownLatch(connectionCount)

      val counter = new AtomicInteger
      var replies = Map.empty[Int, Int]
      for (i ← 0 until connectionCount) {
        replies = replies + (i -> 0)
      }

      val actor = actorOf(new Actor {
        lazy val id = counter.getAndIncrement()
        def receive = {
          case "hit" ⇒ reply(id)
          case "end" ⇒ doneLatch.countDown()
        }
      }, address)

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
      val address = "random-2"

      Deployer.deploy(
        Deploy(
          address,
          None,
          Random,
          NrOfInstances(6),
          NoOpFailureDetector,
          LocalScope))

      val helloLatch = new CountDownLatch(6)
      val stopLatch = new CountDownLatch(6)

      val actor = actorOf(new Actor {
        def receive = {
          case "hello" ⇒ helloLatch.countDown()
        }

        override def postStop() {
          stopLatch.countDown()
        }
      }, address)

      actor ! Broadcast("hello")
      helloLatch.await(5, TimeUnit.SECONDS) must be(true)

      actor.stop()
      stopLatch.await(5, TimeUnit.SECONDS) must be(true)
    }
  }
}
