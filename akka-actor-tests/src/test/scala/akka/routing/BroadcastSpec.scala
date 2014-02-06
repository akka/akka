/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.routing

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Await
import scala.concurrent.duration._
import akka.actor.{ Props, Actor }
import akka.testkit.{ TestLatch, ImplicitSender, DefaultTimeout, AkkaSpec }
import akka.pattern.ask

object BroadcastSpec {
  class TestActor extends Actor {
    def receive = { case _ ⇒ }
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class BroadcastSpec extends AkkaSpec with DefaultTimeout with ImplicitSender {
  import BroadcastSpec._

  "broadcast group" must {

    "broadcast message using !" in {
      val doneLatch = new TestLatch(2)

      val counter1 = new AtomicInteger
      val actor1 = system.actorOf(Props(new Actor {
        def receive = {
          case "end"    ⇒ doneLatch.countDown()
          case msg: Int ⇒ counter1.addAndGet(msg)
        }
      }))

      val counter2 = new AtomicInteger
      val actor2 = system.actorOf(Props(new Actor {
        def receive = {
          case "end"    ⇒ doneLatch.countDown()
          case msg: Int ⇒ counter2.addAndGet(msg)
        }
      }))

      val paths = List(actor1, actor2).map(_.path.toString)
      val routedActor = system.actorOf(BroadcastGroup(paths).props())
      routedActor ! 1
      routedActor ! "end"

      Await.ready(doneLatch, remaining)

      counter1.get should be(1)
      counter2.get should be(1)
    }

    "broadcast message using ?" in {
      val doneLatch = new TestLatch(2)

      val counter1 = new AtomicInteger
      val actor1 = system.actorOf(Props(new Actor {
        def receive = {
          case "end" ⇒ doneLatch.countDown()
          case msg: Int ⇒
            counter1.addAndGet(msg)
            sender() ! "ack"
        }
      }))

      val counter2 = new AtomicInteger
      val actor2 = system.actorOf(Props(new Actor {
        def receive = {
          case "end"    ⇒ doneLatch.countDown()
          case msg: Int ⇒ counter2.addAndGet(msg)
        }
      }))

      val paths = List(actor1, actor2).map(_.path.toString)
      val routedActor = system.actorOf(BroadcastGroup(paths).props())
      routedActor ? 1
      routedActor ! "end"

      Await.ready(doneLatch, remaining)

      counter1.get should be(1)
      counter2.get should be(1)
    }
  }

}
