/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import akka.testkit.{ filterEvents, EventFilter }
import akka.dispatch.{ PinnedDispatcher, Dispatchers, Await }
import java.util.concurrent.{ TimeUnit, CountDownLatch }
import akka.testkit.AkkaSpec
import akka.testkit.DefaultTimeout
import akka.pattern.ask
import scala.util.duration._

object SupervisorMiscSpec {
  val config = """
    pinned-dispatcher {
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
          protected def receive = {
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
  }
}
