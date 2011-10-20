/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.testkit._

import java.util.concurrent.{ TimeUnit, CountDownLatch }

object SupervisorHierarchySpec {
  class FireWorkerException(msg: String) extends Exception(msg)

  class CountDownActor(countDown: CountDownLatch) extends Actor {
    protected def receive = { case _ ⇒ }
    override def postRestart(reason: Throwable) = {
      countDown.countDown()
    }
  }
}

class SupervisorHierarchySpec extends AkkaSpec {
  import SupervisorHierarchySpec._

  "A Supervisor Hierarchy" must {

    "restart manager and workers in AllForOne" in {
      val countDown = new CountDownLatch(4)

      val boss = actorOf(Props(self ⇒ { case _ ⇒ }).withFaultHandler(OneForOneStrategy(List(classOf[Exception]), None, None)))

      val manager = actorOf(Props(new CountDownActor(countDown)).withFaultHandler(AllForOneStrategy(List(), None, None)).withSupervisor(boss))

      val workerProps = Props(new CountDownActor(countDown)).withSupervisor(manager)
      val workerOne, workerTwo, workerThree = actorOf(workerProps)

      filterException[ActorKilledException] {
        workerOne ! Kill

        // manager + all workers should be restarted by only killing a worker
        // manager doesn't trap exits, so boss will restart manager

        assert(countDown.await(2, TimeUnit.SECONDS))
      }
    }

    "send notification to supervisor when permanent failure" in {
      val countDownMessages = new CountDownLatch(1)
      val countDownMax = new CountDownLatch(1)
      val boss = actorOf(Props(new Actor {
        val crasher = self startsMonitoring actorOf(Props(new CountDownActor(countDownMessages)).withSupervisor(self))

        protected def receive = {
          case "killCrasher"    ⇒ crasher ! Kill
          case Terminated(_, _) ⇒ countDownMax.countDown()
        }
      }).withFaultHandler(OneForOneStrategy(List(classOf[Throwable]), 1, 5000)))

      filterException[ActorKilledException] {
        boss ! "killCrasher"
        boss ! "killCrasher"

        assert(countDownMessages.await(2, TimeUnit.SECONDS))
        assert(countDownMax.await(2, TimeUnit.SECONDS))
      }
    }
  }
}

