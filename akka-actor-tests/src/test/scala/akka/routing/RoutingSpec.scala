package akka.routing

import akka.routing._
import java.util.concurrent.atomic.AtomicInteger
import akka.actor.{ ActorRef, Actor }
import collection.mutable.LinkedList
import akka.routing.Routing.Broadcast
import java.util.concurrent.{ CountDownLatch, TimeUnit }
import akka.testkit.AkkaSpec

object RoutingSpec {

  class TestActor extends Actor with Serializable {
    def receive = {
      case _ ⇒
        println("Hello")
    }
  }
}

class RoutingSpec extends AkkaSpec {

  import akka.routing.RoutingSpec._

  "direct router" must {
    "be started when constructed" in {
      val actor1 = createActor[TestActor]

      val props = RoutedProps(() ⇒ new DirectRouter, List(actor1))
      val actor = app.routing.actorOf(props, "foo")
      actor.isShutdown must be(false)
    }

    "throw IllegalArgumentException at construction when no connections" in {
      try {
        val props = RoutedProps(() ⇒ new DirectRouter, List())
        app.routing.actorOf(props, "foo")
        fail()
      } catch {
        case e: IllegalArgumentException ⇒
      }
    }

    "send message to connection" in {
      val doneLatch = new CountDownLatch(1)

      val counter = new AtomicInteger(0)
      val connection1 = createActor(new Actor {
        def receive = {
          case "end" ⇒ doneLatch.countDown()
          case _     ⇒ counter.incrementAndGet
        }
      })

      val props = RoutedProps(() ⇒ new DirectRouter, List(connection1))
      val routedActor = app.routing.actorOf(props, "foo")
      routedActor ! "hello"
      routedActor ! "end"

      doneLatch.await(5, TimeUnit.SECONDS) must be(true)

      counter.get must be(1)
    }

    "deliver a broadcast message" in {
      val doneLatch = new CountDownLatch(1)

      val counter1 = new AtomicInteger
      val connection1 = createActor(new Actor {
        def receive = {
          case "end"    ⇒ doneLatch.countDown()
          case msg: Int ⇒ counter1.addAndGet(msg)
        }
      })

      val props = RoutedProps(() ⇒ new DirectRouter, List(connection1))
      val actor = app.routing.actorOf(props, "foo")

      actor ! Broadcast(1)
      actor ! "end"

      doneLatch.await(5, TimeUnit.SECONDS) must be(true)

      counter1.get must be(1)
    }
  }

  "round robin router" must {

    "be started when constructed" in {
      val actor1 = createActor[TestActor]

      val props = RoutedProps(() ⇒ new RoundRobinRouter, List(actor1))
      val actor = app.routing.actorOf(props, "foo")
      actor.isShutdown must be(false)
    }

    "throw IllegalArgumentException at construction when no connections" in {
      try {
        val props = RoutedProps(() ⇒ new RoundRobinRouter, List())
        app.routing.actorOf(props, "foo")
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

        val connection = createActor(new Actor {
          def receive = {
            case "end"    ⇒ doneLatch.countDown()
            case msg: Int ⇒ counters.get(i).get.addAndGet(msg)
          }
        })
        connections = connections :+ connection
      }

      //create the routed actor.
      val props = RoutedProps(() ⇒ new RoundRobinRouter, connections)
      val actor = app.routing.actorOf(props, "foo")

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
      val connection1 = createActor(new Actor {
        def receive = {
          case "end"    ⇒ doneLatch.countDown()
          case msg: Int ⇒ counter1.addAndGet(msg)
        }
      })

      val counter2 = new AtomicInteger
      val connection2 = createActor(new Actor {
        def receive = {
          case "end"    ⇒ doneLatch.countDown()
          case msg: Int ⇒ counter2.addAndGet(msg)
        }
      })

      val props = RoutedProps(() ⇒ new RoundRobinRouter, List(connection1, connection2))
      val actor = app.routing.actorOf(props, "foo")

      actor ! Broadcast(1)
      actor ! Broadcast("end")

      doneLatch.await(5, TimeUnit.SECONDS) must be(true)

      counter1.get must be(1)
      counter2.get must be(1)
    }

    "fail to deliver a broadcast message using the ?" in {
      val doneLatch = new CountDownLatch(1)

      val counter1 = new AtomicInteger
      val connection1 = createActor(new Actor {
        def receive = {
          case "end" ⇒ doneLatch.countDown()
          case _     ⇒ counter1.incrementAndGet()
        }
      })

      val props = RoutedProps(() ⇒ new RoundRobinRouter, List(connection1))
      val actor = app.routing.actorOf(props, "foo")

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

      val actor1 = createActor[TestActor]

      val props = RoutedProps(() ⇒ new RandomRouter, List(actor1))
      val actor = app.routing.actorOf(props, "foo")
      actor.isShutdown must be(false)
    }

    "throw IllegalArgumentException at construction when no connections" in {
      try {
        val props = RoutedProps(() ⇒ new RandomRouter, List())
        app.routing.actorOf(props, "foo")
        fail()
      } catch {
        case e: IllegalArgumentException ⇒
      }
    }

    "deliver messages in a random fashion" ignore {

    }

    "deliver a broadcast message" in {
      val doneLatch = new CountDownLatch(2)

      val counter1 = new AtomicInteger
      val connection1 = createActor(new Actor {
        def receive = {
          case "end"    ⇒ doneLatch.countDown()
          case msg: Int ⇒ counter1.addAndGet(msg)
        }
      })

      val counter2 = new AtomicInteger
      val connection2 = createActor(new Actor {
        def receive = {
          case "end"    ⇒ doneLatch.countDown()
          case msg: Int ⇒ counter2.addAndGet(msg)
        }
      })

      val props = RoutedProps(() ⇒ new RandomRouter, List(connection1, connection2))
      val actor = app.routing.actorOf(props, "foo")

      actor ! Broadcast(1)
      actor ! Broadcast("end")

      doneLatch.await(5, TimeUnit.SECONDS) must be(true)

      counter1.get must be(1)
      counter2.get must be(1)
    }

    "fail to deliver a broadcast message using the ?" in {
      val doneLatch = new CountDownLatch(1)

      val counter1 = new AtomicInteger
      val connection1 = createActor(new Actor {
        def receive = {
          case "end" ⇒ doneLatch.countDown()
          case _     ⇒ counter1.incrementAndGet()
        }
      })

      val props = RoutedProps(() ⇒ new RandomRouter, List(connection1))
      val actor = app.routing.actorOf(props, "foo")

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
}
