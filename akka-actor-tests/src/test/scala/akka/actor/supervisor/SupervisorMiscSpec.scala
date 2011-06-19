/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.actor

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import akka.dispatch.Dispatchers
import akka.config.Supervision.{ SupervisorConfig, OneForOneStrategy, Supervise, Permanent }
import java.util.concurrent.CountDownLatch

class SupervisorMiscSpec extends WordSpec with MustMatchers {
  "A Supervisor" should {

    "restart a crashing actor and its dispatcher for any dispatcher" in {
      val countDownLatch = new CountDownLatch(4)

      val actor1 = Actor.actorOf(new Actor {
        self.dispatcher = Dispatchers.newThreadBasedDispatcher(self)
        override def postRestart(cause: Throwable) { countDownLatch.countDown() }

        protected def receive = {
          case "kill" ⇒ throw new Exception("killed")
          case _      ⇒ println("received unknown message")
        }
      }).start()

      val actor2 = Actor.actorOf(new Actor {
        self.dispatcher = Dispatchers.newThreadBasedDispatcher(self)
        override def postRestart(cause: Throwable) { countDownLatch.countDown() }

        protected def receive = {
          case "kill" ⇒ throw new Exception("killed")
          case _      ⇒ println("received unknown message")
        }
      }).start()

      val actor3 = Actor.actorOf(new Actor {
        self.dispatcher = Dispatchers.newExecutorBasedEventDrivenDispatcher("test").build
        override def postRestart(cause: Throwable) { countDownLatch.countDown() }

        protected def receive = {
          case "kill" ⇒ throw new Exception("killed")
          case _      ⇒ println("received unknown message")
        }
      }).start()

      val actor4 = Actor.actorOf(new Actor {
        self.dispatcher = Dispatchers.newThreadBasedDispatcher(self)
        override def postRestart(cause: Throwable) { countDownLatch.countDown() }

        protected def receive = {
          case "kill" ⇒ throw new Exception("killed")
          case _      ⇒ println("received unknown message")
        }
      }).start()

      val sup = Supervisor(
        SupervisorConfig(
          OneForOneStrategy(List(classOf[Exception]), 3, 5000),
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
