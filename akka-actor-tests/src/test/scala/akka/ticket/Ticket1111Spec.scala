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

    "return response, even if one of the connections has stopped" ignore {

      val actor = Routing.actorOf("foo", List(newActor(0), newActor(1)),
        new ScatterGatherFirstCompletedRouter()).start()

      actor ! Broadcast(Stop(Some(0)))

      (actor ? Broadcast(0)).get.asInstanceOf[Int] must be(1)

    }

    "throw an exception, if all the connections have stopped" in {

      val actor = Routing.actorOf("foo", List(newActor(0), newActor(1)),
        new ScatterGatherFirstCompletedRouter()).start()

      actor ! Broadcast(Stop())

      (intercept[RoutingException] {
        actor ? Broadcast(0)
      }) must not be (null)

    }

    "return the first response from connections, when all of them replied" in {

      val actor = Routing.actorOf("foo", List(newActor(0), newActor(1)),
        new ScatterGatherFirstCompletedRouter()).start()

      (actor ? Broadcast("Hi!")).get.asInstanceOf[Int] must be(0)

    }

    "return the first response from connections, when some of them failed to reply" in {

      val actor = Routing.actorOf("foo", List(newActor(0), newActor(1)),
        new ScatterGatherFirstCompletedRouter()).start()

      (actor ? Broadcast(0)).get.asInstanceOf[Int] must be(1)

    }

    "be started when constructed" in {

      val actor = Routing.actorOf("foo", List(newActor(0)),
        new ScatterGatherFirstCompletedRouter()).start()

      actor.isRunning must be(true)

    }

    "throw IllegalArgumentException at construction when no connections" in {
      try {
        Routing.actorOf("foo", List(),
          new ScatterGatherFirstCompletedRouter()).start()
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

      val actor = Routing.actorOf("foo", connections, new ScatterGatherFirstCompletedRouter()).start()

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

      val actor = Routing.actorOf("foo", List(connection1, connection2), new ScatterGatherFirstCompletedRouter()).start()

      actor ! Broadcast(1)
      actor ! Broadcast("end")

      doneLatch.await(5, TimeUnit.SECONDS) must be(true)

      counter1.get must be(1)
      counter2.get must be(1)
    }

    case class Stop(id: Option[Int] = None)

    def newActor(id: Int) = actorOf(new Actor {
      def receive = {
        case Stop(None)                     ⇒ self.stop()
        case Stop(Some(_id)) if (_id == id) ⇒ self.stop()
        case _id: Int if (_id == id)        ⇒
        case _                              ⇒ Thread sleep 100 * id; self tryReply id
      }
    }).start()

  }

}
