/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.routing

import java.util.concurrent.atomic.AtomicInteger
import akka.actor._
import collection.mutable.LinkedList
import java.util.concurrent.{ CountDownLatch, TimeUnit }
import akka.testkit._
import akka.util.duration._
import akka.dispatch.Await

object RoutingSpec {

  class TestActor extends Actor {
    def receive = {
      case _ ⇒
        println("Hello")
    }
  }

  class Echo extends Actor {
    def receive = {
      case _ ⇒ sender ! self
    }
  }

}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class RoutingSpec extends AkkaSpec with DefaultTimeout with ImplicitSender {

  val impl = system.asInstanceOf[ActorSystemImpl]

  import akka.routing.RoutingSpec._

  "routers in general" must {

    "evict terminated routees" in {
      val router = system.actorOf(Props[Echo].withRouter(RoundRobinRouter(2)))
      router ! ""
      router ! ""
      val c1, c2 = expectMsgType[ActorRef]
      watch(router)
      watch(c2)
      system.stop(c2)
      expectMsg(Terminated(c2))
      // it might take a while until the Router has actually processed the Terminated message
      awaitCond {
        router ! ""
        router ! ""
        val res = receiveWhile(100 millis, messages = 2) {
          case x: ActorRef ⇒ x
        }
        res == Seq(c1, c1)
      }
      system.stop(c1)
      expectMsg(Terminated(router))
    }

  }

  "no router" must {
    "be started when constructed" in {
      val routedActor = system.actorOf(Props[TestActor].withRouter(NoRouter))
      routedActor.isTerminated must be(false)
    }

    "send message to connection" in {
      val doneLatch = new CountDownLatch(1)

      val counter = new AtomicInteger(0)

      class Actor1 extends Actor {
        def receive = {
          case "end" ⇒ doneLatch.countDown()
          case _     ⇒ counter.incrementAndGet
        }
      }

      val routedActor = system.actorOf(Props(new Actor1).withRouter(NoRouter))
      routedActor ! "hello"
      routedActor ! "end"

      doneLatch.await(5, TimeUnit.SECONDS) must be(true)

      counter.get must be(1)
    }
  }

  "round robin router" must {
    "be started when constructed" in {
      val routedActor = system.actorOf(Props[TestActor].withRouter(RoundRobinRouter(nrOfInstances = 1)))
      routedActor.isTerminated must be(false)
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
      var actors = new LinkedList[ActorRef]
      var counters = new LinkedList[AtomicInteger]
      for (i ← 0 until connectionCount) {
        counters = counters :+ new AtomicInteger()

        val actor = system.actorOf(Props(new Actor {
          def receive = {
            case "end"    ⇒ doneLatch.countDown()
            case msg: Int ⇒ counters.get(i).get.addAndGet(msg)
          }
        }))
        actors = actors :+ actor
      }

      val routedActor = system.actorOf(Props[TestActor].withRouter(RoundRobinRouter(targets = actors)))

      //send messages to the actor.
      for (i ← 0 until iterationCount) {
        for (k ← 0 until connectionCount) {
          routedActor ! (k + 1)
        }
      }

      routedActor ! Broadcast("end")
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

      val routedActor = system.actorOf(Props[TestActor].withRouter(RoundRobinRouter(targets = List(actor1, actor2))))

      routedActor ! Broadcast(1)
      routedActor ! Broadcast("end")

      doneLatch.await(5, TimeUnit.SECONDS) must be(true)

      counter1.get must be(1)
      counter2.get must be(1)
    }
  }

  "random router" must {

    "be started when constructed" in {
      val routedActor = system.actorOf(Props[TestActor].withRouter(RandomRouter(nrOfInstances = 1)))
      routedActor.isTerminated must be(false)
    }

    "deliver a broadcast message" in {
      val doneLatch = new CountDownLatch(2)

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

      val routedActor = system.actorOf(Props[TestActor].withRouter(RandomRouter(targets = List(actor1, actor2))))

      routedActor ! Broadcast(1)
      routedActor ! Broadcast("end")

      doneLatch.await(5, TimeUnit.SECONDS) must be(true)

      counter1.get must be(1)
      counter2.get must be(1)
    }
  }

  "broadcast router" must {
    "be started when constructed" in {
      val routedActor = system.actorOf(Props[TestActor].withRouter(BroadcastRouter(nrOfInstances = 1)))
      routedActor.isTerminated must be(false)
    }

    "broadcast message using !" in {
      val doneLatch = new CountDownLatch(2)

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

      val routedActor = system.actorOf(Props[TestActor].withRouter(BroadcastRouter(targets = List(actor1, actor2))))
      routedActor ! 1
      routedActor ! "end"

      doneLatch.await(5, TimeUnit.SECONDS) must be(true)

      counter1.get must be(1)
      counter2.get must be(1)
    }

    "broadcast message using ?" in {
      val doneLatch = new CountDownLatch(2)

      val counter1 = new AtomicInteger
      val actor1 = system.actorOf(Props(new Actor {
        def receive = {
          case "end" ⇒ doneLatch.countDown()
          case msg: Int ⇒
            counter1.addAndGet(msg)
            sender ! "ack"
        }
      }))

      val counter2 = new AtomicInteger
      val actor2 = system.actorOf(Props(new Actor {
        def receive = {
          case "end"    ⇒ doneLatch.countDown()
          case msg: Int ⇒ counter2.addAndGet(msg)
        }
      }))

      val routedActor = system.actorOf(Props[TestActor].withRouter(BroadcastRouter(targets = List(actor1, actor2))))
      routedActor ? 1
      routedActor ! "end"

      doneLatch.await(5, TimeUnit.SECONDS) must be(true)

      counter1.get must be(1)
      counter2.get must be(1)
    }
  }

  "Scatter-gather router" must {

    "be started when constructed" in {
      val routedActor = system.actorOf(Props[TestActor].withRouter(ScatterGatherFirstCompletedRouter(targets = List(newActor(0)))))
      routedActor.isTerminated must be(false)
    }

    "deliver a broadcast message using the !" in {
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

      val routedActor = system.actorOf(Props[TestActor].withRouter(ScatterGatherFirstCompletedRouter(targets = List(actor1, actor2))))
      routedActor ! Broadcast(1)
      routedActor ! Broadcast("end")

      doneLatch.await

      counter1.get must be(1)
      counter2.get must be(1)
    }

    "return response, even if one of the actors has stopped" in {
      val shutdownLatch = new TestLatch(1)
      val actor1 = newActor(1, Some(shutdownLatch))
      val actor2 = newActor(22, Some(shutdownLatch))
      val routedActor = system.actorOf(Props[TestActor].withRouter(ScatterGatherFirstCompletedRouter(targets = List(actor1, actor2))))

      routedActor ! Broadcast(Stop(Some(1)))
      shutdownLatch.await
      Await.result(routedActor ? Broadcast(0), timeout.duration) must be(22)
    }

    case class Stop(id: Option[Int] = None)

    def newActor(id: Int, shudownLatch: Option[TestLatch] = None) = system.actorOf(Props(new Actor {
      def receive = {
        case Stop(None)                     ⇒ context.stop(self)
        case Stop(Some(_id)) if (_id == id) ⇒ context.stop(self)
        case _id: Int if (_id == id)        ⇒
        case x ⇒ {
          Thread sleep 100 * id
          sender.tell(id)
        }
      }

      override def postStop = {
        shudownLatch foreach (_.countDown())
      }
    }), "Actor:" + id)
  }

  "custom router" must {
    "be started when constructed" in {
      val routedActor = system.actorOf(Props(new TestActor).withRouter(VoteCountRouter()))
      routedActor.isTerminated must be(false)
    }

    "count votes as intended - not as in Florida" in {
      val routedActor = system.actorOf(Props(new TestActor).withRouter(VoteCountRouter()))
      routedActor ! DemocratVote
      routedActor ! DemocratVote
      routedActor ! RepublicanVote
      routedActor ! DemocratVote
      routedActor ! RepublicanVote
      val democratsResult = (routedActor ? DemocratCountResult)
      val republicansResult = (routedActor ? RepublicanCountResult)

      Await.result(democratsResult, 1 seconds)
      Await.result(republicansResult, 1 seconds)

      democratsResult.value must be(Some(Right(3)))
      republicansResult.value must be(Some(Right(2)))
    }

    // DO NOT CHANGE THE COMMENTS BELOW AS THEY ARE USED IN THE DOCUMENTATION

    //#CustomRouter
    //#crMessages
    case object DemocratVote
    case object DemocratCountResult
    case object RepublicanVote
    case object RepublicanCountResult
    //#crMessages

    //#crActors
    class DemocratActor extends Actor {
      val counter = new AtomicInteger(0)

      def receive = {
        case DemocratVote        ⇒ counter.incrementAndGet()
        case DemocratCountResult ⇒ sender ! counter.get
      }
    }

    class RepublicanActor extends Actor {
      val counter = new AtomicInteger(0)

      def receive = {
        case RepublicanVote        ⇒ counter.incrementAndGet()
        case RepublicanCountResult ⇒ sender ! counter.get
      }
    }
    //#crActors

    //#crRouter
    case class VoteCountRouter(nrOfInstances: Int = 0, targets: Iterable[String] = Nil)
      extends RouterConfig {

      //#crRoute
      def createRoute(props: Props,
                      actorContext: ActorContext,
                      ref: RoutedActorRef): Route = {
        val democratActor = actorContext.actorOf(Props(new DemocratActor), "d")
        val republicanActor = actorContext.actorOf(Props(new RepublicanActor), "r")
        val routees = Vector[ActorRef](democratActor, republicanActor)

        //#crRegisterRoutees
        registerRoutees(actorContext, routees)
        //#crRegisterRoutees

        //#crRoutingLogic
        {
          case (sender, message) ⇒
            message match {
              case DemocratVote | DemocratCountResult ⇒
                List(Destination(sender, democratActor))
              case RepublicanVote | RepublicanCountResult ⇒
                List(Destination(sender, republicanActor))
            }
        }
        //#crRoutingLogic
      }
      //#crRoute

    }
    //#crRouter
    //#CustomRouter
  }
}
