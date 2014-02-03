/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.oldrouting

import language.postfixOps

import akka.actor._
import scala.collection.immutable
import akka.testkit._
import scala.concurrent.duration._
import scala.concurrent.Await
import akka.ConfigurationException
import com.typesafe.config.ConfigFactory
import akka.pattern.{ ask, pipe }
import java.util.concurrent.ConcurrentHashMap
import com.typesafe.config.Config
import akka.dispatch.Dispatchers
import akka.util.Collections.EmptyImmutableSeq
import akka.util.Timeout
import java.util.concurrent.atomic.AtomicInteger
import akka.routing._

object RoutingSpec {

  val config = """
    akka.actor.serialize-messages = off
    akka.actor.deployment {
      /router1 {
        router = round-robin
        nr-of-instances = 3
      }
      /router2 {
        router = round-robin
        nr-of-instances = 3
      }
      /router3 {
        router = round-robin
        nr-of-instances = 0
      }
    }
    """

  class TestActor extends Actor {
    def receive = { case _ ⇒ }
  }

  class Echo extends Actor {
    def receive = {
      case _ ⇒ sender() ! self
    }
  }

}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class RoutingSpec extends AkkaSpec(RoutingSpec.config) with DefaultTimeout with ImplicitSender {
  implicit val ec = system.dispatcher
  import RoutingSpec._

  muteDeadLetters(classOf[akka.dispatch.sysmsg.DeathWatchNotification])()

  "routers in general" must {

    "evict terminated routees" in {
      val router = system.actorOf(Props[Echo].withRouter(RoundRobinRouter(2)))
      router ! ""
      router ! ""
      val c1, c2 = expectMsgType[ActorRef]
      watch(router)
      watch(c2)
      system.stop(c2)
      expectTerminated(c2).existenceConfirmed should be(true)
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
      expectTerminated(router).existenceConfirmed should be(true)
    }

    "not terminate when resizer is used" in {
      val latch = TestLatch(1)
      val resizer = new Resizer {
        def isTimeForResize(messageCounter: Long): Boolean = messageCounter == 0
        def resize(currentRoutees: immutable.IndexedSeq[Routee]): Int = {
          latch.countDown()
          2
        }
      }
      val router = system.actorOf(Props[TestActor].withRouter(RoundRobinRouter(resizer = Some(resizer))))
      watch(router)
      Await.ready(latch, remaining)
      router ! CurrentRoutees
      val routees = expectMsgType[RouterRoutees].routees
      routees.size should be(2)
      routees foreach system.stop
      // expect no Terminated
      expectNoMsg(2.seconds)
    }

    "be able to send their routees" in {
      case class TestRun(id: String, names: immutable.Iterable[String], actors: Int)
      val actor = system.actorOf(Props(new Actor {
        def receive = {
          case TestRun(id, names, actors) ⇒
            val routerProps = Props[TestActor].withRouter(
              ScatterGatherFirstCompletedRouter(
                routees = names map { context.actorOf(Props(new TestActor), _) },
                within = 5 seconds))

            1 to actors foreach { i ⇒ context.actorOf(routerProps, id + i).tell(CurrentRoutees, testActor) }
        }
      }))

      val actors = 15
      val names = 1 to 20 map { "routee" + _ } toSet

      actor ! TestRun("test", names, actors)

      1 to actors foreach { _ ⇒
        val routees = expectMsgType[RouterRoutees].routees
        routees.map(_.path.name).toSet should be(names)
      }
      expectNoMsg(500.millis)
    }

    "use configured nr-of-instances when FromConfig" in {
      val router = system.actorOf(Props[TestActor].withRouter(FromConfig), "router1")
      router ! CurrentRoutees
      expectMsgType[RouterRoutees].routees.size should be(3)
      watch(router)
      system.stop(router)
      expectTerminated(router)
    }

    "use configured nr-of-instances when router is specified" in {
      val router = system.actorOf(Props[TestActor].withRouter(RoundRobinRouter(nrOfInstances = 2)), "router2")
      router ! CurrentRoutees
      expectMsgType[RouterRoutees].routees.size should be(3)
      system.stop(router)
    }

    "use specified resizer when resizer not configured" in {
      val latch = TestLatch(1)
      val resizer = new Resizer {
        def isTimeForResize(messageCounter: Long): Boolean = messageCounter == 0
        def resize(currentRoutees: immutable.IndexedSeq[Routee]): Int = {
          latch.countDown()
          3
        }
      }
      val router = system.actorOf(Props[TestActor].withRouter(RoundRobinRouter(resizer = Some(resizer))), "router3")
      Await.ready(latch, remaining)
      router ! CurrentRoutees
      expectMsgType[RouterRoutees].routees.size should be(3)
      system.stop(router)
    }

    "set supplied supervisorStrategy" in {
      //#supervision
      val escalator = OneForOneStrategy() {
        //#custom-strategy
        case e ⇒ testActor ! e; SupervisorStrategy.Escalate
        //#custom-strategy
      }
      val router = system.actorOf(Props.empty.withRouter(
        RoundRobinRouter(1, supervisorStrategy = escalator)))
      //#supervision
      router ! CurrentRoutees
      EventFilter[ActorKilledException](occurrences = 1) intercept {
        expectMsgType[RouterRoutees].routees.head ! Kill
      }
      expectMsgType[ActorKilledException]

      val router2 = system.actorOf(Props.empty.withRouter(RoundRobinRouter(1).withSupervisorStrategy(escalator)))
      router2 ! CurrentRoutees
      EventFilter[ActorKilledException](occurrences = 1) intercept {
        expectMsgType[RouterRoutees].routees.head ! Kill
      }
      expectMsgType[ActorKilledException]
    }

    "set supplied supervisorStrategy for FromConfig" in {
      val escalator = OneForOneStrategy() {
        case e ⇒ testActor ! e; SupervisorStrategy.Escalate
      }
      val router = system.actorOf(Props.empty.withRouter(FromConfig.withSupervisorStrategy(escalator)), "router1")
      router ! CurrentRoutees
      EventFilter[ActorKilledException](occurrences = 1) intercept {
        expectMsgType[RouterRoutees].routees.head ! Kill
      }
      expectMsgType[ActorKilledException]
    }

    "default to all-for-one-always-escalate strategy" in {
      val restarter = OneForOneStrategy() {
        case e ⇒ testActor ! e; SupervisorStrategy.Restart
      }
      val supervisor = system.actorOf(Props(new Supervisor(restarter)))
      supervisor ! Props(new Actor {
        def receive = {
          case x: String ⇒ throw new Exception(x)
        }
        override def postRestart(reason: Throwable): Unit = testActor ! "restarted"
      }).withRouter(RoundRobinRouter(3))
      val router = expectMsgType[ActorRef]
      EventFilter[Exception]("die", occurrences = 1) intercept {
        router ! "die"
      }
      expectMsgType[Exception].getMessage should be("die")
      expectMsg("restarted")
      expectMsg("restarted")
      expectMsg("restarted")
    }

    "start in-line for context.actorOf()" in {
      system.actorOf(Props(new Actor {
        def receive = {
          case "start" ⇒
            context.actorOf(Props(new Actor {
              def receive = { case x ⇒ sender() ! x }
            }).withRouter(RoundRobinRouter(2))) ? "hello" pipeTo sender()
        }
      })) ! "start"
      expectMsg("hello")
    }

  }

  "no router" must {
    "be started when constructed" in {
      val routedActor = system.actorOf(Props[TestActor].withRouter(NoRouter))
      routedActor.isTerminated should be(false)
    }

    "send message to connection" in {
      class Actor1 extends Actor {
        def receive = {
          case msg ⇒ testActor forward msg
        }
      }

      val routedActor = system.actorOf(Props(new Actor1).withRouter(NoRouter))
      routedActor ! "hello"
      routedActor ! "end"

      expectMsg("hello")
      expectMsg("end")
    }
  }

  "round robin router" must {
    "be started when constructed" in {
      val routedActor = system.actorOf(Props[TestActor].withRouter(RoundRobinRouter(nrOfInstances = 1)))
      routedActor.isTerminated should be(false)
    }

    //In this test a bunch of actors are created and each actor has its own counter.
    //to test round robin, the routed actor receives the following sequence of messages 1 2 3 .. 1 2 3 .. 1 2 3 which it
    //uses to increment his counter.
    //So after n iteration, the first actor his counter should be 1*n, the second 2*n etc etc.
    "deliver messages in a round robin fashion" in {
      val connectionCount = 10
      val iterationCount = 10
      val doneLatch = new TestLatch(connectionCount)

      //lets create some connections.
      @volatile var actors = immutable.IndexedSeq[ActorRef]()
      @volatile var counters = immutable.IndexedSeq[AtomicInteger]()
      for (i ← 0 until connectionCount) {
        counters = counters :+ new AtomicInteger()

        val actor = system.actorOf(Props(new Actor {
          def receive = {
            case "end"    ⇒ doneLatch.countDown()
            case msg: Int ⇒ counters(i).addAndGet(msg)
          }
        }))
        actors = actors :+ actor
      }

      val routedActor = system.actorOf(Props[TestActor].withRouter(RoundRobinRouter(routees = actors)))

      //send messages to the actor.
      for (i ← 0 until iterationCount) {
        for (k ← 0 until connectionCount) {
          routedActor ! (k + 1)
        }
      }

      routedActor ! Broadcast("end")
      //now wait some and do validations.
      Await.ready(doneLatch, remaining)

      for (i ← 0 until connectionCount)
        counters(i).get should be((iterationCount * (i + 1)))
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

      val routedActor = system.actorOf(Props[TestActor].withRouter(RoundRobinRouter(routees = List(actor1, actor2))))

      routedActor ! Broadcast(1)
      routedActor ! Broadcast("end")

      Await.ready(doneLatch, remaining)

      counter1.get should be(1)
      counter2.get should be(1)
    }
  }

  "random router" must {

    "be started when constructed" in {
      val routedActor = system.actorOf(Props[TestActor].withRouter(RandomRouter(nrOfInstances = 1)))
      routedActor.isTerminated should be(false)
    }

    "deliver a broadcast message" in {
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

      val routedActor = system.actorOf(Props[TestActor].withRouter(RandomRouter(routees = List(actor1, actor2))))

      routedActor ! Broadcast(1)
      routedActor ! Broadcast("end")

      Await.ready(doneLatch, remaining)

      counter1.get should be(1)
      counter2.get should be(1)
    }
  }

  "smallest mailbox router" must {
    "be started when constructed" in {
      val routedActor = system.actorOf(Props[TestActor].withRouter(SmallestMailboxRouter(nrOfInstances = 1)))
      routedActor.isTerminated should be(false)
    }

    "deliver messages to idle actor" in {
      val usedActors = new ConcurrentHashMap[Int, String]()
      val router = system.actorOf(Props(new Actor {
        def receive = {
          case (busy: TestLatch, receivedLatch: TestLatch) ⇒
            usedActors.put(0, self.path.toString)
            self ! "another in busy mailbox"
            receivedLatch.countDown()
            Await.ready(busy, TestLatch.DefaultTimeout)
          case (msg: Int, receivedLatch: TestLatch) ⇒
            usedActors.put(msg, self.path.toString)
            receivedLatch.countDown()
          case s: String ⇒
        }
      }).withRouter(SmallestMailboxRouter(3)))

      val busy = TestLatch(1)
      val received0 = TestLatch(1)
      router ! ((busy, received0))
      Await.ready(received0, TestLatch.DefaultTimeout)

      val received1 = TestLatch(1)
      router ! ((1, received1))
      Await.ready(received1, TestLatch.DefaultTimeout)

      val received2 = TestLatch(1)
      router ! ((2, received2))
      Await.ready(received2, TestLatch.DefaultTimeout)

      val received3 = TestLatch(1)
      router ! ((3, received3))
      Await.ready(received3, TestLatch.DefaultTimeout)

      busy.countDown()

      val busyPath = usedActors.get(0)
      busyPath should not be (null)

      val path1 = usedActors.get(1)
      val path2 = usedActors.get(2)
      val path3 = usedActors.get(3)

      path1 should not be (busyPath)
      path2 should not be (busyPath)
      path3 should not be (busyPath)

    }
  }

  "broadcast router" must {
    "be started when constructed" in {
      val routedActor = system.actorOf(Props[TestActor].withRouter(BroadcastRouter(nrOfInstances = 1)))
      routedActor.isTerminated should be(false)
    }

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

      val routedActor = system.actorOf(Props[TestActor].withRouter(BroadcastRouter(routees = List(actor1, actor2))))
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

      val routedActor = system.actorOf(Props[TestActor].withRouter(BroadcastRouter(routees = List(actor1, actor2))))
      routedActor ? 1
      routedActor ! "end"

      Await.ready(doneLatch, remaining)

      counter1.get should be(1)
      counter2.get should be(1)
    }
  }

  "Scatter-gather router" must {

    "be started when constructed" in {
      val routedActor = system.actorOf(Props[TestActor].withRouter(
        ScatterGatherFirstCompletedRouter(routees = List(newActor(0)), within = 1 seconds)))
      routedActor.isTerminated should be(false)
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

      val routedActor = system.actorOf(Props[TestActor].withRouter(
        ScatterGatherFirstCompletedRouter(routees = List(actor1, actor2), within = 1 seconds)))
      routedActor ! Broadcast(1)
      routedActor ! Broadcast("end")

      Await.ready(doneLatch, TestLatch.DefaultTimeout)

      counter1.get should be(1)
      counter2.get should be(1)
    }

    "return response, even if one of the actors has stopped" in {
      val shutdownLatch = new TestLatch(1)
      val actor1 = newActor(1, Some(shutdownLatch))
      val actor2 = newActor(14, Some(shutdownLatch))
      val routedActor = system.actorOf(Props[TestActor].withRouter(
        ScatterGatherFirstCompletedRouter(routees = List(actor1, actor2), within = 3 seconds)))

      routedActor ! Broadcast(Stop(Some(1)))
      Await.ready(shutdownLatch, TestLatch.DefaultTimeout)
      Await.result(routedActor ? Broadcast(0), timeout.duration) should be(14)
    }

    case class Stop(id: Option[Int] = None)

    def newActor(id: Int, shudownLatch: Option[TestLatch] = None) = system.actorOf(Props(new Actor {
      def receive = {
        case Stop(None)                     ⇒ context.stop(self)
        case Stop(Some(_id)) if (_id == id) ⇒ context.stop(self)
        case _id: Int if (_id == id)        ⇒
        case x ⇒ {
          Thread sleep 100 * id
          sender() ! id
        }
      }

      override def postStop = {
        shudownLatch foreach (_.countDown())
      }
    }), "Actor:" + id)
  }

  "router FromConfig" must {
    "throw suitable exception when not configured" in {
      val e = intercept[ConfigurationException] {
        system.actorOf(Props[TestActor].withRouter(FromConfig), "routerNotDefined")
      }
      e.getMessage should include("routerNotDefined")
    }

    "allow external configuration" in {
      val sys = ActorSystem("FromConfig", ConfigFactory
        .parseString("akka.actor.deployment./routed.router=round-robin")
        .withFallback(system.settings.config))
      try {
        sys.actorOf(Props.empty.withRouter(FromConfig), "routed")
      } finally {
        shutdown(sys)
      }
    }

  }

}
