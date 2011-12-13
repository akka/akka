/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import akka.testkit.{ filterEvents, EventFilter }
import akka.dispatch.{ PinnedDispatcher, Dispatchers, Await }
import java.util.concurrent.{ TimeUnit, CountDownLatch }
import akka.testkit.AkkaSpec
import akka.testkit.DefaultTimeout

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class SupervisorMiscSpec extends AkkaSpec with DefaultTimeout {

  "A Supervisor" must {

    "restart a crashing actor and its dispatcher for any dispatcher" in {
      filterEvents(EventFilter[Exception]("Kill")) {
        val countDownLatch = new CountDownLatch(4)

        val supervisor = system.actorOf(Props[Supervisor].withFaultHandler(OneForOneStrategy(List(classOf[Exception]), 3, 5000)))

        val workerProps = Props(new Actor {
          override def postRestart(cause: Throwable) { countDownLatch.countDown() }
          protected def receive = {
            case "status" ⇒ this.sender ! "OK"
            case _        ⇒ this.self.stop()
          }
        })

        val actor1, actor2 = Await.result((supervisor ? workerProps.withDispatcher(system.dispatcherFactory.newPinnedDispatcher("pinned"))).mapTo[ActorRef], timeout.duration)

        val actor3 = Await.result((supervisor ? workerProps.withDispatcher(system.dispatcherFactory.newDispatcher("test").build)).mapTo[ActorRef], timeout.duration)

        val actor4 = Await.result((supervisor ? workerProps.withDispatcher(system.dispatcherFactory.newPinnedDispatcher("pinned"))).mapTo[ActorRef], timeout.duration)

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
