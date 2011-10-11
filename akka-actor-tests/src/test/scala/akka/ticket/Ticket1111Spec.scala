package akka.ticket

import akka.routing._
import akka.actor.{ ActorRef, Actor }
import java.util.concurrent.atomic.AtomicInteger
import collection.mutable.LinkedList
import akka.routing.Routing.Broadcast
import akka.testkit._

class Ticket1111Spec extends AkkaSpec {

  "Scatter-gather router" must {

    "return response, even if one of the connections has stopped" in {

      val shutdownLatch = new TestLatch(1)

      val props = RoutedProps()
        .withConnections(List(newActor(0, Some(shutdownLatch)), newActor(1, Some(shutdownLatch))))
        .withRouter(() ⇒ new ScatterGatherFirstCompletedRouter())

      val actor = app.routing.actorOf(props, "foo")

      actor ! Broadcast(Stop(Some(0)))

      shutdownLatch.await

      (actor ? Broadcast(0)).get.asInstanceOf[Int] must be(1)
    }

    "throw an exception, if all the connections have stopped" in {

      val shutdownLatch = new TestLatch(2)

      val props = RoutedProps()
        .withConnections(List(newActor(0, Some(shutdownLatch)), newActor(1, Some(shutdownLatch))))
        .withRouter(() ⇒ new ScatterGatherFirstCompletedRouter())

      val actor = app.routing.actorOf(props, "foo")

      actor ! Broadcast(Stop())

      shutdownLatch.await

      (intercept[RoutingException] {
        actor ? Broadcast(0)
      }) must not be (null)

    }

    "return the first response from connections, when all of them replied" in {

      val props = RoutedProps()
        .withConnections(List(newActor(0), newActor(1)))
        .withRouter(() ⇒ new ScatterGatherFirstCompletedRouter())

      val actor = app.routing.actorOf(props, "foo")

      (actor ? Broadcast("Hi!")).get.asInstanceOf[Int] must be(0)

    }

    "return the first response from connections, when some of them failed to reply" in {
      val props = RoutedProps()
        .withConnections(List(newActor(0), newActor(1)))
        .withRouter(() ⇒ new ScatterGatherFirstCompletedRouter())

      val actor = app.routing.actorOf(props, "foo")

      (actor ? Broadcast(0)).get.asInstanceOf[Int] must be(1)

    }

    "be started when constructed" in {
      val props = RoutedProps()
        .withConnections(List(newActor(0)))
        .withRouter(() ⇒ new ScatterGatherFirstCompletedRouter())
      val actor = app.routing.actorOf(props, "foo")

      actor.isShutdown must be(false)

    }

    "throw IllegalArgumentException at construction when no connections" in {
      val props = RoutedProps()
        .withConnections(List())
        .withRouter(() ⇒ new ScatterGatherFirstCompletedRouter())

      try {
        app.routing.actorOf(props, "foo")
        fail()
      } catch {
        case e: IllegalArgumentException ⇒
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

        val connection = createActor(new Actor {
          def receive = {
            case "end"    ⇒ doneLatch.countDown()
            case msg: Int ⇒ counters.get(i).get.addAndGet(msg)
          }
        })
        connections = connections :+ connection
      }

      val props = RoutedProps()
        .withConnections(connections)
        .withRouter(() ⇒ new ScatterGatherFirstCompletedRouter())

      val actor = app.routing.actorOf(props, "foo")

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

      val props = RoutedProps.apply()
        .withConnections(List(connection1, connection2))
        .withRouter(() ⇒ new ScatterGatherFirstCompletedRouter())

      val actor = app.routing.actorOf(props, "foo")

      actor ! Broadcast(1)
      actor ! Broadcast("end")

      doneLatch.await

      counter1.get must be(1)
      counter2.get must be(1)
    }

    case class Stop(id: Option[Int] = None)

    def newActor(id: Int, shudownLatch: Option[TestLatch] = None) = createActor(new Actor {
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
