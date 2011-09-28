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

  // "direct router" must {

  //   "be able to shut down its instance" in {
  //     val address = "direct-0"

  //     Deployer.deploy(
  //       Deploy(
  //         address,
  //         None,
  //         Direct,
  //         ReplicationFactor(1),
  //         RemoveConnectionOnFirstFailureLocalFailureDetector,
  //         LocalScope))

  //     val helloLatch = new CountDownLatch(1)
  //     val stopLatch = new CountDownLatch(1)

  //     val actor = actorOf(new Actor {
  //       def receive = {
  //         case "hello" ⇒ helloLatch.countDown()
  //       }

  //       override def postStop() {
  //         stopLatch.countDown()
  //       }
  //     }, address)

  //     actor ! "hello"

  //     helloLatch.await(5, TimeUnit.SECONDS) must be(true)

  //     actor.stop()

  //     stopLatch.await(5, TimeUnit.SECONDS) must be(true)
  //   }

  //   "send message to connection" in {
  //     val address = "direct-1"

  //     Deployer.deploy(
  //       Deploy(
  //         address,
  //         None,
  //         Direct,
  //         ReplicationFactor(1),
  //         RemoveConnectionOnFirstFailureLocalFailureDetector,
  //         LocalScope))

  //     val doneLatch = new CountDownLatch(1)

  //     val counter = new AtomicInteger(0)
  //     val actor = actorOf(new Actor {
  //       def receive = {
  //         case "end" ⇒ doneLatch.countDown()
  //         case _     ⇒ counter.incrementAndGet()
  //       }
  //     }, address)

  //     actor ! "hello"
  //     actor ! "end"

  //     doneLatch.await(5, TimeUnit.SECONDS) must be(true)

  //     counter.get must be(1)
  //   }

  //   "deliver a broadcast message" in {
  //     val address = "direct-2"

  //     Deployer.deploy(
  //       Deploy(
  //         address,
  //         None,
  //         Direct,
  //         ReplicationFactor(1),
  //         RemoveConnectionOnFirstFailureLocalFailureDetector,
  //         LocalScope))

  //     val doneLatch = new CountDownLatch(1)

  //     val counter1 = new AtomicInteger
  //     val actor = actorOf(new Actor {
  //       def receive = {
  //         case "end"    ⇒ doneLatch.countDown()
  //         case msg: Int ⇒ counter1.addAndGet(msg)
  //       }
  //     }, address)

  //     actor ! Broadcast(1)
  //     actor ! "end"

  //     doneLatch.await(5, TimeUnit.SECONDS) must be(true)

  //     counter1.get must be(1)
  //   }
  // }

  "round robin router" must {

    "be able to shut down its instance" in {
      val address = "round-robin-0"

      Deployer.deploy(
        Deploy(
          address,
          None,
          RoundRobin,
          ReplicationFactor(5),
          RemoveConnectionOnFirstFailureLocalFailureDetector,
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
          ReplicationFactor(10),
          RemoveConnectionOnFirstFailureLocalFailureDetector,
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
          ReplicationFactor(5),
          RemoveConnectionOnFirstFailureLocalFailureDetector,
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
          ReplicationFactor(7),
          RemoveConnectionOnFirstFailureLocalFailureDetector,
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
          ReplicationFactor(10),
          RemoveConnectionOnFirstFailureLocalFailureDetector,
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
          ReplicationFactor(6),
          RemoveConnectionOnFirstFailureLocalFailureDetector,
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
