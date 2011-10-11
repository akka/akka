package akka.routing

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import akka.routing._
import akka.config.ConfigurationException
import java.util.concurrent.atomic.AtomicInteger
import akka.actor.Actor._
import akka.actor.{ ActorRef, Actor }
import collection.mutable.LinkedList
import akka.routing.Routing.Broadcast
import java.util.concurrent.{ CountDownLatch, TimeUnit }
import akka.testkit._

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

      val props = RoutedProps().withDirectRouter.withLocalConnections(List(actor1))
      val actor = Actor.actorOf(props, "foo")
      actor.isShutdown must be(false)
    }

    "throw ConfigurationException at construction when no connections" in {
      try {
        val props = RoutedProps().withDirectRouter
        Actor.actorOf(props, "foo")
        fail()
      } catch {
        case e: ConfigurationException ⇒
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

      val props = RoutedProps().withDirectRouter.withLocalConnections(List(connection1))
      val routedActor = Actor.actorOf(props, "foo")
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

      val props = RoutedProps().withDirectRouter.withLocalConnections(List(connection1))
      val actor = Actor.actorOf(props, "foo")

      actor ! Broadcast(1)
      actor ! "end"

      doneLatch.await(5, TimeUnit.SECONDS) must be(true)

      counter1.get must be(1)
    }
  }

  "round robin router" must {

    "be started when constructed" in {
      val actor1 = Actor.actorOf[TestActor]

      val props = RoutedProps().withRoundRobinRouter.withLocalConnections(List(actor1))
      val actor = Actor.actorOf(props, "foo")
      actor.isShutdown must be(false)
    }

    "throw ConfigurationException at construction when no connections" in {
      try {
        val props = RoutedProps().withRoundRobinRouter
        Actor.actorOf(props, "foo")
        fail()
      } catch {
        case e: ConfigurationException ⇒
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
      val props = RoutedProps().withRoundRobinRouter.withLocalConnections(connections)
      val actor = Actor.actorOf(props, "foo")

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

      val props = RoutedProps().withRoundRobinRouter.withLocalConnections(List(connection1, connection2))
      val actor = Actor.actorOf(props, "foo")

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

      val props = RoutedProps().withRoundRobinRouter.withLocalConnections(List(connection1))
      val actor = Actor.actorOf(props, "foo")

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

      val props = RoutedProps().withRandomRouter.withLocalConnections(List(actor1))
      val actor = Actor.actorOf(props, "foo")
      actor.isShutdown must be(false)
    }

    "throw ConfigurationException at construction when no connections" in {
      try {
        val props = RoutedProps().withRandomRouter
        Actor.actorOf(props, "foo")
        fail()
      } catch {
        case e: ConfigurationException ⇒
      }
    }

    "deliver messages in a random fashion" ignore {

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

      val props = RoutedProps().withRandomRouter.withLocalConnections(List(connection1, connection2))
      val actor = Actor.actorOf(props, "foo")

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

      val props = RoutedProps().withRandomRouter.withLocalConnections(List(connection1))
      val actor = Actor.actorOf(props, "foo")

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

  "Scatter-gather router" must {

    "return response, even if one of the connections has stopped" in {

      val shutdownLatch = new TestLatch(1)

      val props = RoutedProps()
        .withLocalConnections(List(newActor(0, Some(shutdownLatch)), newActor(1, Some(shutdownLatch))))
        .withRouter(() ⇒ new ScatterGatherFirstCompletedRouter())

      val actor = Actor.actorOf(props, "foo")

      actor ! Broadcast(Stop(Some(0)))

      shutdownLatch.await

      (actor ? Broadcast(0)).get.asInstanceOf[Int] must be(1)
    }

    "throw an exception, if all the connections have stopped" in {

      val shutdownLatch = new TestLatch(2)

      val props = RoutedProps()
        .withLocalConnections(List(newActor(0, Some(shutdownLatch)), newActor(1, Some(shutdownLatch))))
        .withRouter(() ⇒ new ScatterGatherFirstCompletedRouter())

      val actor = Actor.actorOf(props, "foo")

      actor ! Broadcast(Stop())

      shutdownLatch.await

      (intercept[RoutingException] {
        actor ? Broadcast(0)
      }) must not be (null)

    }

    "return the first response from connections, when all of them replied" in {

      val props = RoutedProps()
        .withLocalConnections(List(newActor(0), newActor(1)))
        .withRouter(() ⇒ new ScatterGatherFirstCompletedRouter())

      val actor = Actor.actorOf(props, "foo")

      (actor ? Broadcast("Hi!")).get.asInstanceOf[Int] must be(0)

    }

    "return the first response from connections, when some of them failed to reply" in {
      val props = RoutedProps()
        .withLocalConnections(List(newActor(0), newActor(1)))
        .withRouter(() ⇒ new ScatterGatherFirstCompletedRouter())

      val actor = Actor.actorOf(props, "foo")

      (actor ? Broadcast(0)).get.asInstanceOf[Int] must be(1)
    }

    "be started when constructed" in {
      val props = RoutedProps()
        .withLocalConnections(List(newActor(0)))
        .withRouter(() ⇒ new ScatterGatherFirstCompletedRouter())
      val actor = Actor.actorOf(props, "foo")

      actor.isShutdown must be(false)

    }

    "throw ConfigurationException at construction when no connections" in {
      val props = RoutedProps()
        .withLocalConnections(List())
        .withRouter(() ⇒ new ScatterGatherFirstCompletedRouter())

      try {
        Actor.actorOf(props, "foo")
        fail()
      } catch {
        case e: ConfigurationException ⇒
      }
    }

    "deliver one-way messages in a round robin fashion" in {
      val connectionCount = 10
      val iterationCount = 10
      val doneLatch = new TestLatch(connectionCount)

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

      val props = RoutedProps()
        .withLocalConnections(connections)
        .withRouter(() ⇒ new ScatterGatherFirstCompletedRouter())

      val actor = Actor.actorOf(props, "foo")

      for (i ← 0 until iterationCount) {
        for (k ← 0 until connectionCount) {
          actor ! (k + 1)
        }
      }

      actor ! Broadcast("end")

      doneLatch.await

      for (i ← 0 until connectionCount) {
        val counter = counters.get(i).get
        counter.get must be((iterationCount * (i + 1)))
      }
    }

    "deliver a broadcast message using the !" in {
      val doneLatch = new TestLatch(2)

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

      val props = RoutedProps.apply()
        .withLocalConnections(List(connection1, connection2))
        .withRouter(() ⇒ new ScatterGatherFirstCompletedRouter())

      val actor = Actor.actorOf(props, "foo")

      actor ! Broadcast(1)
      actor ! Broadcast("end")

      doneLatch.await

      counter1.get must be(1)
      counter2.get must be(1)
    }

    case class Stop(id: Option[Int] = None)

    def newActor(id: Int, shudownLatch: Option[TestLatch] = None) = actorOf(new Actor {
      def receive = {
        case Stop(None)                     ⇒ self.stop()
        case Stop(Some(_id)) if (_id == id) ⇒ self.stop()
        case _id: Int if (_id == id)        ⇒
        case _                              ⇒ Thread sleep 100 * id; tryReply(id)
      }

      override def postStop = {
        shudownLatch foreach (_.countDown())
      }
    })
  }
}
