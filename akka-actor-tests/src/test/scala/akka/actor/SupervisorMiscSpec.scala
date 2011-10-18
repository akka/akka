/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import akka.testkit.{ filterEvents, EventFilter }
import akka.dispatch.{ PinnedDispatcher, Dispatchers }
import java.util.concurrent.{ TimeUnit, CountDownLatch }
import akka.testkit.AkkaSpec

class SupervisorMiscSpec extends AkkaSpec {

  "A Supervisor" must {

    "restart a crashing actor and its dispatcher for any dispatcher" in {
      filterEvents(EventFilter[Exception]("Kill")) {
        val countDownLatch = new CountDownLatch(4)

        val supervisor = actorOf(Props(new Actor {
          def receive = { case _ ⇒ }
        }).withFaultHandler(OneForOneStrategy(List(classOf[Exception]), 3, 5000)))

        val workerProps = Props(new Actor {
          override def postRestart(cause: Throwable) { countDownLatch.countDown() }

          protected def receive = {
            case "status" ⇒ this.reply("OK")
            case _        ⇒ this.self.stop()
          }
        }).withSupervisor(supervisor)

        val actor1 = actorOf(workerProps.withDispatcher(app.dispatcherFactory.newPinnedDispatcher("pinned")))

        val actor2 = actorOf(workerProps.withDispatcher(app.dispatcherFactory.newPinnedDispatcher("pinned")))

        val actor3 = actorOf(workerProps.withDispatcher(app.dispatcherFactory.newDispatcher("test").build))

        val actor4 = actorOf(workerProps.withDispatcher(app.dispatcherFactory.newPinnedDispatcher("pinned")))

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
