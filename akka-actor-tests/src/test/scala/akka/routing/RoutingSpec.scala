/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.routing

import java.util.concurrent.atomic.AtomicInteger
import akka.actor._
import scala.collection.mutable.LinkedList
import akka.testkit._
import akka.util.duration._
import akka.dispatch.Await
import akka.util.Duration
import akka.config.ConfigurationException
import com.typesafe.config.ConfigFactory
import akka.pattern.ask
import java.util.concurrent.ConcurrentHashMap
import com.typesafe.config.Config
import akka.dispatch.Dispatchers

object RoutingSpec {

  val config = """
    akka.actor.deployment {
      /router1 {
        router = round-robin
        nr-of-instances = 3
      }
      /myrouter {
        router = "akka.routing.RoutingSpec$MyRouter"
        foo = bar
      }
    }
    """

  class TestActor extends Actor {
    def receive = { case _ ⇒ }
  }

  class Echo extends Actor {
    def receive = {
      case _ ⇒ sender ! self
    }
  }

  class MyRouter(config: Config) extends RouterConfig {
    val foo = config.getString("foo")
    def createRoute(routeeProps: Props, routeeProvider: RouteeProvider): Route = {
      val routees = IndexedSeq(routeeProvider.context.actorOf(Props[Echo]))
      routeeProvider.registerRoutees(routees)

      {
        case (sender, message) ⇒ Nil
      }
    }
    def routerDispatcher: String = Dispatchers.DefaultDispatcherId
    def supervisorStrategy: SupervisorStrategy = SupervisorStrategy.defaultStrategy
  }

}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class RoutingSpec extends AkkaSpec(RoutingSpec.config) with DefaultTimeout with ImplicitSender {

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

    "be able to send their routees" in {
      val doneLatch = new TestLatch(1)

      class TheActor extends Actor {
        val routee1 = context.actorOf(Props[TestActor], "routee1")
        val routee2 = context.actorOf(Props[TestActor], "routee2")
        val routee3 = context.actorOf(Props[TestActor], "routee3")
        val router = context.actorOf(Props[TestActor].withRouter(
          ScatterGatherFirstCompletedRouter(
            routees = List(routee1, routee2, routee3),
            within = 5 seconds)))

        def receive = {
          case RouterRoutees(iterable) ⇒
            iterable.exists(_.path.name == "routee1") must be(true)
            iterable.exists(_.path.name == "routee2") must be(true)
            iterable.exists(_.path.name == "routee3") must be(true)
            doneLatch.countDown()
          case "doIt" ⇒
            router ! CurrentRoutees
        }
      }

      val theActor = system.actorOf(Props(new TheActor), "theActor")
      theActor ! "doIt"
      Await.ready(doneLatch, 1 seconds)
    }

    "use configured nr-of-instances when FromConfig" in {
      val router = system.actorOf(Props[TestActor].withRouter(FromConfig), "router1")
      Await.result(router ? CurrentRoutees, 5 seconds).asInstanceOf[RouterRoutees].routees.size must be(3)
      system.stop(router)
    }

    "use configured nr-of-instances when router is specified" in {
      val router = system.actorOf(Props[TestActor].withRouter(RoundRobinRouter(nrOfInstances = 2)), "router1")
      Await.result(router ? CurrentRoutees, 5 seconds).asInstanceOf[RouterRoutees].routees.size must be(3)
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
      EventFilter[ActorKilledException](occurrences = 2) intercept {
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
      EventFilter[Exception]("die", occurrences = 2) intercept {
        router ! "die"
      }
      expectMsgType[Exception].getMessage must be("die")
      expectMsg("restarted")
      expectMsg("restarted")
      expectMsg("restarted")
    }

  }

  "no router" must {
    "be started when constructed" in {
      val routedActor = system.actorOf(Props[TestActor].withRouter(NoRouter))
      routedActor.isTerminated must be(false)
    }

    "send message to connection" in {
      val doneLatch = new TestLatch(1)

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

      Await.ready(doneLatch, 5 seconds)

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
      val doneLatch = new TestLatch(connectionCount)

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

      val routedActor = system.actorOf(Props[TestActor].withRouter(RoundRobinRouter(routees = actors)))

      //send messages to the actor.
      for (i ← 0 until iterationCount) {
        for (k ← 0 until connectionCount) {
          routedActor ! (k + 1)
        }
      }

      routedActor ! Broadcast("end")
      //now wait some and do validations.
      Await.ready(doneLatch, 5 seconds)

      for (i ← 0 until connectionCount) {
        val counter = counters.get(i).get
        counter.get must be((iterationCount * (i + 1)))
      }
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

      Await.ready(doneLatch, 5 seconds)

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

      Await.ready(doneLatch, 5 seconds)

      counter1.get must be(1)
      counter2.get must be(1)
    }
  }

  "smallest mailbox router" must {
    "be started when constructed" in {
      val routedActor = system.actorOf(Props[TestActor].withRouter(SmallestMailboxRouter(nrOfInstances = 1)))
      routedActor.isTerminated must be(false)
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
      router ! (busy, received0)
      Await.ready(received0, TestLatch.DefaultTimeout)

      val received1 = TestLatch(1)
      router ! (1, received1)
      Await.ready(received1, TestLatch.DefaultTimeout)

      val received2 = TestLatch(1)
      router ! (2, received2)
      Await.ready(received2, TestLatch.DefaultTimeout)

      val received3 = TestLatch(1)
      router ! (3, received3)
      Await.ready(received3, TestLatch.DefaultTimeout)

      busy.countDown()

      val busyPath = usedActors.get(0)
      busyPath must not be (null)

      val path1 = usedActors.get(1)
      val path2 = usedActors.get(2)
      val path3 = usedActors.get(3)

      path1 must not be (busyPath)
      path2 must not be (busyPath)
      path3 must not be (busyPath)

    }
  }

  "broadcast router" must {
    "be started when constructed" in {
      val routedActor = system.actorOf(Props[TestActor].withRouter(BroadcastRouter(nrOfInstances = 1)))
      routedActor.isTerminated must be(false)
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

      Await.ready(doneLatch, 5 seconds)

      counter1.get must be(1)
      counter2.get must be(1)
    }

    "broadcast message using ?" in {
      val doneLatch = new TestLatch(2)

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

      val routedActor = system.actorOf(Props[TestActor].withRouter(BroadcastRouter(routees = List(actor1, actor2))))
      routedActor ? 1
      routedActor ! "end"

      Await.ready(doneLatch, 5 seconds)

      counter1.get must be(1)
      counter2.get must be(1)
    }
  }

  "Scatter-gather router" must {

    "be started when constructed" in {
      val routedActor = system.actorOf(Props[TestActor].withRouter(
        ScatterGatherFirstCompletedRouter(routees = List(newActor(0)), within = 1 seconds)))
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

      val routedActor = system.actorOf(Props[TestActor].withRouter(
        ScatterGatherFirstCompletedRouter(routees = List(actor1, actor2), within = 1 seconds)))
      routedActor ! Broadcast(1)
      routedActor ! Broadcast("end")

      Await.ready(doneLatch, TestLatch.DefaultTimeout)

      counter1.get must be(1)
      counter2.get must be(1)
    }

    "return response, even if one of the actors has stopped" in {
      val shutdownLatch = new TestLatch(1)
      val actor1 = newActor(1, Some(shutdownLatch))
      val actor2 = newActor(14, Some(shutdownLatch))
      val routedActor = system.actorOf(Props[TestActor].withRouter(
        ScatterGatherFirstCompletedRouter(routees = List(actor1, actor2), within = 3 seconds)))

      routedActor ! Broadcast(Stop(Some(1)))
      Await.ready(shutdownLatch, TestLatch.DefaultTimeout)
      Await.result(routedActor ? Broadcast(0), timeout.duration) must be(14)
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

  "router FromConfig" must {
    "throw suitable exception when not configured" in {
      intercept[ConfigurationException] {
        system.actorOf(Props.empty.withRouter(FromConfig))
      }.getMessage.contains("application.conf") must be(true)
    }

    "allow external configuration" in {
      val sys = ActorSystem("FromConfig", ConfigFactory
        .parseString("akka.actor.deployment./routed.router=round-robin")
        .withFallback(system.settings.config))
      try {
        sys.actorOf(Props.empty.withRouter(FromConfig), "routed")
      } finally {
        sys.shutdown()
      }
    }
    "support custom router" in {
      val myrouter = system.actorOf(Props().withRouter(FromConfig), "myrouter")
      myrouter.isTerminated must be(false)
    }
  }

  "custom router" must {
    "be started when constructed" in {
      val routedActor = system.actorOf(Props[TestActor].withRouter(VoteCountRouter()))
      routedActor.isTerminated must be(false)
    }

    "count votes as intended - not as in Florida" in {
      val routedActor = system.actorOf(Props().withRouter(VoteCountRouter()))
      routedActor ! DemocratVote
      routedActor ! DemocratVote
      routedActor ! RepublicanVote
      routedActor ! DemocratVote
      routedActor ! RepublicanVote
      val democratsResult = (routedActor ? DemocratCountResult)
      val republicansResult = (routedActor ? RepublicanCountResult)

      Await.result(democratsResult, 1 seconds) === 3
      Await.result(republicansResult, 1 seconds) === 2
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
      var counter = 0

      def receive = {
        case DemocratVote        ⇒ counter += 1
        case DemocratCountResult ⇒ sender ! counter
      }
    }

    class RepublicanActor extends Actor {
      var counter = 0

      def receive = {
        case RepublicanVote        ⇒ counter += 1
        case RepublicanCountResult ⇒ sender ! counter
      }
    }
    //#crActors

    //#crRouter
    case class VoteCountRouter() extends RouterConfig {

      def routerDispatcher: String = Dispatchers.DefaultDispatcherId
      def supervisorStrategy: SupervisorStrategy = SupervisorStrategy.defaultStrategy

      //#crRoute
      def createRoute(routeeProps: Props, routeeProvider: RouteeProvider): Route = {
        val democratActor = routeeProvider.context.actorOf(Props(new DemocratActor()), "d")
        val republicanActor = routeeProvider.context.actorOf(Props(new RepublicanActor()), "r")
        val routees = Vector[ActorRef](democratActor, republicanActor)

        //#crRegisterRoutees
        routeeProvider.registerRoutees(routees)
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
