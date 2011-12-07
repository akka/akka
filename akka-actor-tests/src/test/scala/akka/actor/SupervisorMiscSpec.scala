/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import akka.testkit.{ filterEvents, EventFilter }
import akka.dispatch.{ PinnedDispatcher, Dispatchers }
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

        val actor1 = (supervisor ? workerProps.withDispatcher(system.dispatcherFactory.newPinnedDispatcher("pinned"))).as[ActorRef].get

        val actor2 = (supervisor ? workerProps.withDispatcher(system.dispatcherFactory.newPinnedDispatcher("pinned"))).as[ActorRef].get

        val actor3 = (supervisor ? workerProps.withDispatcher(system.dispatcherFactory.newDispatcher("test").build)).as[ActorRef].get

        val actor4 = (supervisor ? workerProps.withDispatcher(system.dispatcherFactory.newPinnedDispatcher("pinned"))).as[ActorRef].get

        actor1 ! Kill
        actor2 ! Kill
        actor3 ! Kill
        actor4 ! Kill

        countDownLatch.await(10, TimeUnit.SECONDS)
        assert((actor1 ? "status").as[String].get == "OK", "actor1 is shutdown")
        assert((actor2 ? "status").as[String].get == "OK", "actor2 is shutdown")
        assert((actor3 ? "status").as[String].get == "OK", "actor3 is shutdown")
        assert((actor4 ? "status").as[String].get == "OK", "actor4 is shutdown")
      }
    }
  }
}
