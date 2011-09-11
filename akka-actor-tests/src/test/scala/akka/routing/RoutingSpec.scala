package akka.routing

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import akka.routing._
import java.util.concurrent.atomic.AtomicInteger
import akka.actor.Actor._
import akka.actor.{ ActorRef, Actor }
import collection.mutable.LinkedList
import akka.routing.Routing.Broadcast
import java.util.concurrent.{ CountDownLatch, TimeUnit }

object RoutingSpec {

  class TestActor extends Actor with Serializable {
    def receive = {
      case _ ⇒
        println("Hello")
    }
  }
}

class RoutingSpec extends WordSpec with MustMatchers {

  import akka.routing.RoutingSpec._

  "direct router" must {
    "be started when constructed" in {
      val actor1 = Actor.actorOf[TestActor]

      val actor = Routing.actorOf("foo", List(actor1), RouterType.Direct)
      actor.isRunning must be(true)
    }

    "throw IllegalArgumentException at construction when no connections" in {
      try {
        Routing.actorOf("foo", List(), RouterType.Direct)
        fail()
      } catch {
        case e: IllegalArgumentException ⇒
      }
    }

    "send message to connection" in {
      val doneLatch = new CountDownLatch(1)

      val counter = new AtomicInteger(0)
      val connection1 = actorOf(new Actor {
        def receive = {
          case "end" ⇒ doneLatch.countDown()
          case _     ⇒ counter.incrementAndGet
        }
      })

      val routedActor = Routing.actorOf("foo", List(connection1), RouterType.Direct)
      routedActor ! "hello"
      routedActor ! "end"

      doneLatch.await(5, TimeUnit.SECONDS) must be(true)

      counter.get must be(1)
    }

    "deliver a broadcast message" in {
      val doneLatch = new CountDownLatch(1)

      val counter1 = new AtomicInteger
      val connection1 = actorOf(new Actor {
        def receive = {
          case "end"    ⇒ doneLatch.countDown()
          case msg: Int ⇒ counter1.addAndGet(msg)
        }
      })

      val actor = Routing.actorOf("foo", List(connection1), RouterType.Direct)

      actor ! Broadcast(1)
      actor ! "end"

      doneLatch.await(5, TimeUnit.SECONDS) must be(true)

      counter1.get must be(1)
    }
  }

  "round robin router" must {

    "be started when constructed" in {
      val actor1 = Actor.actorOf[TestActor]

      val actor = Routing.actorOf("foo", List(actor1), RouterType.RoundRobin)
      actor.isRunning must be(true)
    }

    "throw IllegalArgumentException at construction when no connections" in {
      try {
        Routing.actorOf("foo", List(), RouterType.RoundRobin)
        fail()
      } catch {
        case e: IllegalArgumentException ⇒
      }
    }

    //In this test a bunch of actors are created and each actor has its own counter.
    //to test round robin, the routed actor receives the following sequence of messages 1 2 3 .. 1 2 3 .. 1 2 3 which it
    //uses to increment his counter.
    //So after n iteration, the first actor his counter should be 1*n, the second 2*n etc etc.
    "deliver messages in a round robin fashion" in {
      val connectionCount = 10
      val iterationCount = 10
      val doneLatch = new CountDownLatch(connectionCount)

      //lets create some connections.
      var connections = new LinkedList[ActorRef]
      var counters = new LinkedList[AtomicInteger]
      for (i ← 0 until connectionCount) {
        counters = counters :+ new AtomicInteger()

        val connection = actorOf(new Actor {
          def receive = {
            case "end"    ⇒ doneLatch.countDown()
            case msg: Int ⇒ counters.get(i).get.addAndGet(msg)
          }
        })
        connections = connections :+ connection
      }

      //create the routed actor.
      val actor = Routing.actorOf("foo", connections, RouterType.RoundRobin)

      //send messages to the actor.
      for (i ← 0 until iterationCount) {
        for (k ← 0 until connectionCount) {
          actor ! (k + 1)
        }
      }

      actor ! Broadcast("end")
      //now wait some and do validations.
      doneLatch.await(5, TimeUnit.SECONDS) must be(true)

      for (i ← 0 until connectionCount) {
        val counter = counters.get(i).get
        counter.get must be((iterationCount * (i + 1)))
      }
    }

    "deliver a broadcast message using the !" in {
      val doneLatch = new CountDownLatch(2)

      val counter1 = new AtomicInteger
      val connection1 = actorOf(new Actor {
        def receive = {
          case "end"    ⇒ doneLatch.countDown()
          case msg: Int ⇒ counter1.addAndGet(msg)
        }
      })

      val counter2 = new AtomicInteger
      val connection2 = actorOf(new Actor {
        def receive = {
          case "end"    ⇒ doneLatch.countDown()
          case msg: Int ⇒ counter2.addAndGet(msg)
        }
      })

      val actor = Routing.actorOf("foo", List(connection1, connection2), RouterType.RoundRobin)

      actor ! Broadcast(1)
      actor ! Broadcast("end")

      doneLatch.await(5, TimeUnit.SECONDS) must be(true)

      counter1.get must be(1)
      counter2.get must be(1)
    }

    "fail to deliver a broadcast message using the ?" in {
      val doneLatch = new CountDownLatch(1)

      val counter1 = new AtomicInteger
      val connection1 = actorOf(new Actor {
        def receive = {
          case "end" ⇒ doneLatch.countDown()
          case _     ⇒ counter1.incrementAndGet()
        }
      })

      val actor = Routing.actorOf("foo", List(connection1), RouterType.RoundRobin)

      try {
        actor ? Broadcast(1)
        fail()
      } catch {
        case e: RoutingException ⇒
      }

      actor ! "end"
      doneLatch.await(5, TimeUnit.SECONDS) must be(true)
      counter1.get must be(0)
    }
  }

  "random router" must {

    "be started when constructed" in {

      val actor1 = Actor.actorOf[TestActor]

      val actor = Routing.actorOf("foo", List(actor1), RouterType.Random)
      actor.isRunning must be(true)
    }

    "throw IllegalArgumentException at construction when no connections" in {
      try {
        Routing.actorOf("foo", List(), RouterType.Random)
        fail()
      } catch {
        case e: IllegalArgumentException ⇒
      }
    }

    "deliver messages in a random fashion" in {

    }

    "deliver a broadcast message" in {
      val doneLatch = new CountDownLatch(2)

      val counter1 = new AtomicInteger
      val connection1 = actorOf(new Actor {
        def receive = {
          case "end"    ⇒ doneLatch.countDown()
          case msg: Int ⇒ counter1.addAndGet(msg)
        }
      })

      val counter2 = new AtomicInteger
      val connection2 = actorOf(new Actor {
        def receive = {
          case "end"    ⇒ doneLatch.countDown()
          case msg: Int ⇒ counter2.addAndGet(msg)
        }
      })

      val actor = Routing.actorOf("foo", List(connection1, connection2), RouterType.Random)

      actor ! Broadcast(1)
      actor ! Broadcast("end")

      doneLatch.await(5, TimeUnit.SECONDS) must be(true)

      counter1.get must be(1)
      counter2.get must be(1)
    }

    "fail to deliver a broadcast message using the ?" in {
      val doneLatch = new CountDownLatch(1)

      val counter1 = new AtomicInteger
      val connection1 = actorOf(new Actor {
        def receive = {
          case "end" ⇒ doneLatch.countDown()
          case _     ⇒ counter1.incrementAndGet()
        }
      })

      val actor = Routing.actorOf("foo", List(connection1), RouterType.Random)

      try {
        actor ? Broadcast(1)
        fail()
      } catch {
        case e: RoutingException ⇒
      }

      actor ! "end"
      doneLatch.await(5, TimeUnit.SECONDS) must be(true)
      counter1.get must be(0)
    }
  }

  "least cpu router" must {
    "throw IllegalArgumentException when constructed" in {
      val actor1 = Actor.actorOf[TestActor]

      try {
        Routing.actorOf("foo", List(actor1), RouterType.LeastCPU)
      } catch {
        case e: IllegalArgumentException ⇒
      }
    }
  }

  "least ram router" must {
    "throw IllegalArgumentException when constructed" in {
      val actor1 = Actor.actorOf[TestActor]

      try {
        Routing.actorOf("foo", List(actor1), RouterType.LeastRAM)
      } catch {
        case e: IllegalArgumentException ⇒
      }
    }
  }

  "smallest mailbox" must {
    "throw IllegalArgumentException when constructed" in {
      val actor1 = Actor.actorOf[TestActor]

      try {
        Routing.actorOf("foo", List(actor1), RouterType.LeastMessages)
      } catch {
        case e: IllegalArgumentException ⇒
      }
    }
  }
}
