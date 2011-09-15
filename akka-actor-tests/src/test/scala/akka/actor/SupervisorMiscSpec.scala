/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import akka.config.Supervision.{ SupervisorConfig, OneForOnePermanentStrategy, Supervise, Permanent }
import java.util.concurrent.CountDownLatch
import akka.testkit.{ filterEvents, EventFilter }
import akka.dispatch.{ PinnedDispatcher, Dispatchers }

class SupervisorMiscSpec extends WordSpec with MustMatchers {
  "A Supervisor" should {

    "restart a crashing actor and its dispatcher for any dispatcher" in {
      filterEvents(EventFilter[Exception]("killed")) {
        val countDownLatch = new CountDownLatch(4)

        val actor1 = Actor.actorOf(Props(new Actor {
          override def postRestart(cause: Throwable) { countDownLatch.countDown() }

          protected def receive = {
            case "kill" ⇒ throw new Exception("killed")
            case _      ⇒ println("received unknown message")
          }
        }).withDispatcher(new PinnedDispatcher()))

        val actor2 = Actor.actorOf(Props(new Actor {
          override def postRestart(cause: Throwable) { countDownLatch.countDown() }

          protected def receive = {
            case "kill" ⇒ throw new Exception("killed")
            case _      ⇒ println("received unknown message")
          }
        }).withDispatcher(new PinnedDispatcher()))

        val actor3 = Actor.actorOf(Props(new Actor {
          override def postRestart(cause: Throwable) { countDownLatch.countDown() }

          protected def receive = {
            case "kill" ⇒ throw new Exception("killed")
            case _      ⇒ println("received unknown message")
          }
        }).withDispatcher(Dispatchers.newDispatcher("test").build))

        val actor4 = Actor.actorOf(Props(new Actor {
          override def postRestart(cause: Throwable) { countDownLatch.countDown() }

          protected def receive = {
            case "kill" ⇒ throw new Exception("killed")
            case _      ⇒ println("received unknown message")
          }
        }).withDispatcher(new PinnedDispatcher()))

        val sup = Supervisor(
          SupervisorConfig(
            OneForOnePermanentStrategy(List(classOf[Exception]), 3, 5000),
            Supervise(actor1, Permanent) ::
              Supervise(actor2, Permanent) ::
              Supervise(actor3, Permanent) ::
              Supervise(actor4, Permanent) ::
              Nil))

        actor1 ! "kill"
        actor2 ! "kill"
        actor3 ! "kill"
        actor4 ! "kill"

        countDownLatch.await()
        assert(!actor1.isShutdown, "actor1 is shutdown")
        assert(!actor2.isShutdown, "actor2 is shutdown")
        assert(!actor3.isShutdown, "actor3 is shutdown")
        assert(!actor4.isShutdown, "actor4 is shutdown")
      }
    }
  }
}
