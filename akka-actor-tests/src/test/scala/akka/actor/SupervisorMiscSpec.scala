/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import language.postfixOps

import akka.testkit.{ filterEvents, EventFilter }
import scala.concurrent.Await
import akka.dispatch.{ PinnedDispatcher, Dispatchers }
import java.util.concurrent.{ TimeUnit, CountDownLatch }
import akka.testkit.AkkaSpec
import akka.testkit.DefaultTimeout
import akka.pattern.ask
import scala.concurrent.util.duration._
import akka.util.NonFatal

object SupervisorMiscSpec {
  val config = """
    pinned-dispatcher {
      executor = thread-pool-executor
      type = PinnedDispatcher
    }
    test-dispatcher {
    }
    """
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class SupervisorMiscSpec extends AkkaSpec(SupervisorMiscSpec.config) with DefaultTimeout {

  "A Supervisor" must {

    "restart a crashing actor and its dispatcher for any dispatcher" in {
      filterEvents(EventFilter[Exception]("Kill")) {
        val countDownLatch = new CountDownLatch(4)

        val supervisor = system.actorOf(Props(new Supervisor(
          OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 5 seconds)(List(classOf[Exception])))))

        val workerProps = Props(new Actor {
          override def postRestart(cause: Throwable) { countDownLatch.countDown() }
          def receive = {
            case "status" ⇒ this.sender ! "OK"
            case _        ⇒ this.context.stop(self)
          }
        })

        val actor1, actor2 = Await.result((supervisor ? workerProps.withDispatcher("pinned-dispatcher")).mapTo[ActorRef], timeout.duration)

        val actor3 = Await.result((supervisor ? workerProps.withDispatcher("test-dispatcher")).mapTo[ActorRef], timeout.duration)

        val actor4 = Await.result((supervisor ? workerProps.withDispatcher("pinned-dispatcher")).mapTo[ActorRef], timeout.duration)

        actor1 ! Kill
        actor2 ! Kill
        actor3 ! Kill
        actor4 ! Kill

        countDownLatch.await(10, TimeUnit.SECONDS)
        assert(Await.result(actor1 ? "status", timeout.duration) == "OK", "actor1 is shutdown")
        assert(Await.result(actor2 ? "status", timeout.duration) == "OK", "actor2 is shutdown")
        assert(Await.result(actor3 ? "status", timeout.duration) == "OK", "actor3 is shutdown")
        assert(Await.result(actor4 ? "status", timeout.duration) == "OK", "actor4 is shutdown")
      }
    }

    "be able to create named children in its constructor" in {
      val a = system.actorOf(Props(new Actor {
        context.actorOf(Props.empty, "bob")
        def receive = {
          case x: Exception ⇒ throw x
        }
        override def preStart(): Unit = testActor ! "preStart"
      }))
      val m = "weird message"
      EventFilter[Exception](m, occurrences = 1) intercept {
        a ! new Exception(m)
      }
      expectMsg("preStart")
      expectMsg("preStart")
      a.isTerminated must be(false)
    }

    "be able to recreate child when old child is Terminated" in {
      val parent = system.actorOf(Props(new Actor {
        val kid = context.watch(context.actorOf(Props.empty, "foo"))
        def receive = {
          case Terminated(`kid`) ⇒
            try {
              val newKid = context.actorOf(Props.empty, "foo")
              val result =
                if (newKid eq kid) "Failure: context.actorOf returned the same instance!"
                else if (!kid.isTerminated) "Kid is zombie"
                else if (newKid.isTerminated) "newKid was stillborn"
                else if (kid.path != newKid.path) "The kids do not share the same path"
                else "green"
              testActor ! result
            } catch {
              case NonFatal(e) ⇒ testActor ! e
            }
          case "engage" ⇒ context.stop(kid)
        }
      }))
      parent ! "engage"
      expectMsg("green")
    }

    "not be able to recreate child when old child is alive" in {
      val parent = system.actorOf(Props(new Actor {
        def receive = {
          case "engage" ⇒
            try {
              val kid = context.actorOf(Props.empty, "foo")
              context.stop(kid)
              context.actorOf(Props.empty, "foo")
              testActor ! "red"
            } catch {
              case e: InvalidActorNameException ⇒ testActor ! "green"
            }
        }
      }))
      parent ! "engage"
      expectMsg("green")
    }

    "be able to create a similar kid in the fault handling strategy" in {
      val parent = system.actorOf(Props(new Actor {

        override val supervisorStrategy = new OneForOneStrategy()(SupervisorStrategy.defaultStrategy.decider) {
          override def handleChildTerminated(context: ActorContext, child: ActorRef, children: Iterable[ActorRef]): Unit = {
            val newKid = context.actorOf(Props.empty, child.path.name)
            testActor ! {
              if ((newKid ne child) && newKid.path == child.path) "green"
              else "red"
            }
          }
        }

        def receive = {
          case "engage" ⇒ context.stop(context.actorOf(Props.empty, "Robert"))
        }
      }))
      parent ! "engage"
      expectMsg("green")
    }

  }
}
