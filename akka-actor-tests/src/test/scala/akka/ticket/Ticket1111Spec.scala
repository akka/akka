package akka.ticket

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import akka.routing._
import akka.actor.Actor._
import akka.actor.{ ActorRef, Actor }
import java.util.concurrent.atomic.AtomicInteger
import collection.mutable.LinkedList
import akka.routing.Routing.Broadcast
import java.util.concurrent.{ CountDownLatch, TimeUnit }

class Ticket1111Spec extends WordSpec with MustMatchers {

  "Scatter-gather router" must {

    "return response, even if one of the connections has stopped" in {

      val shutdownLatch = new CountDownLatch(1)

      val props = RoutedProps.apply()
        .withDeployId("foo")
        .withConnections(List(newActor(0, Some(shutdownLatch)), newActor(1, Some(shutdownLatch))))
        .withRouter(() ⇒ new ScatterGatherFirstCompletedRouter())

      val actor = Routing.actorOf(props)

      actor ! Broadcast(Stop(Some(0)))

      shutdownLatch.await(5, TimeUnit.SECONDS) must be(true)

      (actor ? Broadcast(0)).get.asInstanceOf[Int] must be(1)
    }

    "throw an exception, if all the connections have stopped" in {

      val shutdownLatch = new CountDownLatch(2)

      val props = RoutedProps.apply()
        .withDeployId("foo")
        .withConnections(List(newActor(0, Some(shutdownLatch)), newActor(1, Some(shutdownLatch))))
        .withRouter(() ⇒ new ScatterGatherFirstCompletedRouter())

      val actor = Routing.actorOf(props)

      actor ! Broadcast(Stop())

      shutdownLatch.await(5, TimeUnit.SECONDS) must be(true)

      (intercept[RoutingException] {
        actor ? Broadcast(0)
      }) must not be (null)

    }

    "return the first response from connections, when all of them replied" in {

      val props = RoutedProps.apply()
        .withDeployId("foo")
        .withConnections(List(newActor(0), newActor(1)))
        .withRouter(() ⇒ new ScatterGatherFirstCompletedRouter())

      val actor = Routing.actorOf(props)

      (actor ? Broadcast("Hi!")).get.asInstanceOf[Int] must be(0)

    }

    "return the first response from connections, when some of them failed to reply" in {
      val props = RoutedProps.apply()
        .withDeployId("foo")
        .withConnections(List(newActor(0), newActor(1)))
        .withRouter(() ⇒ new ScatterGatherFirstCompletedRouter())

      val actor = Routing.actorOf(props)

      (actor ? Broadcast(0)).get.asInstanceOf[Int] must be(1)

    }

    "be started when constructed" in {
      val props = RoutedProps.apply()
        .withDeployId("foo")
        .withConnections(List(newActor(0)))
        .withRouter(() ⇒ new ScatterGatherFirstCompletedRouter())
      val actor = Routing.actorOf(props)

      actor.isRunning must be(true)

    }

    "throw IllegalArgumentException at construction when no connections" in {
      val props = RoutedProps.apply()
        .withDeployId("foo")
        .withConnections(List())
        .withRouter(() ⇒ new ScatterGatherFirstCompletedRouter())

      try {
        Routing.actorOf(props)
        fail()
      } catch {
        case e: IllegalArgumentException ⇒
      }
    }

    "deliver one-way messages in a round robin fashion" in {
      val connectionCount = 10
      val iterationCount = 10
      val doneLatch = new CountDownLatch(connectionCount)

      var connections = new LinkedList[ActorRef]
      var counters = new LinkedList[AtomicInteger]
      for (i ← 0 until connectionCount) {
        counters = counters :+ new AtomicInteger()

        val connection = actorOf(new Actor {
          def receive = {
            case "end"    ⇒ doneLatch.countDown()
            case msg: Int ⇒ counters.get(i).get.addAndGet(msg)
          }
        }).start()
        connections = connections :+ connection
      }

      val props = RoutedProps.apply()
        .withDeployId("foo")
        .withConnections(connections)
        .withRouter(() ⇒ new ScatterGatherFirstCompletedRouter())

      val actor = Routing.actorOf(props)

      for (i ← 0 until iterationCount) {
        for (k ← 0 until connectionCount) {
          actor ! (k + 1)
        }
      }

      actor ! Broadcast("end")

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
      }).start()

      val counter2 = new AtomicInteger
      val connection2 = actorOf(new Actor {
        def receive = {
          case "end"    ⇒ doneLatch.countDown()
          case msg: Int ⇒ counter2.addAndGet(msg)
        }
      }).start()

      val props = RoutedProps.apply()
        .withDeployId("foo")
        .withConnections(List(connection1, connection2))
        .withRouter(() ⇒ new ScatterGatherFirstCompletedRouter())

      val actor = Routing.actorOf(props)

      actor ! Broadcast(1)
      actor ! Broadcast("end")

      doneLatch.await(5, TimeUnit.SECONDS) must be(true)

      counter1.get must be(1)
      counter2.get must be(1)
    }

    case class Stop(id: Option[Int] = None)

    def newActor(id: Int, shudownLatch: Option[CountDownLatch] = None) = actorOf(new Actor {
      def receive = {
        case Stop(None)                     ⇒ self.stop(); shudownLatch.map(_.countDown())
        case Stop(Some(_id)) if (_id == id) ⇒ self.stop(); shudownLatch.map(_.countDown())
        case _id: Int if (_id == id)        ⇒
        case _                              ⇒ Thread sleep 100 * id; self tryReply id
      }
    }).start()

  }

}
