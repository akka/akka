/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import org.scalatest.junit.JUnitSuite
import org.junit.Test

import Actor._
import akka.config.Supervision.OneForOneStrategy

import java.util.concurrent.{ TimeUnit, CountDownLatch }

object SupervisorHierarchySpec {
  class FireWorkerException(msg: String) extends Exception(msg)

  class CountDownActor(countDown: CountDownLatch) extends Actor {
    protected def receive = { case _ ⇒ () }
    override def postRestart(reason: Throwable) = countDown.countDown()
  }

  class CrasherActor extends Actor {
    protected def receive = { case _ ⇒ () }
  }
}

class SupervisorHierarchySpec extends JUnitSuite {
  import SupervisorHierarchySpec._

  @Test
  def killWorkerShouldRestartMangerAndOtherWorkers = {
    val countDown = new CountDownLatch(4)

    val workerOne = actorOf(new CountDownActor(countDown))
    val workerTwo = actorOf(new CountDownActor(countDown))
    val workerThree = actorOf(new CountDownActor(countDown))

    val boss = actorOf(new Actor {
      self.faultHandler = OneForOneStrategy(List(classOf[Throwable]), 5, 1000)

      protected def receive = { case _ ⇒ () }
    }).start()

    val manager = actorOf(new CountDownActor(countDown))
    boss.startLink(manager)

    manager.startLink(workerOne)
    manager.startLink(workerTwo)
    manager.startLink(workerThree)

    workerOne ! Death(workerOne, new FireWorkerException("Fire the worker!"))

    // manager + all workers should be restarted by only killing a worker
    // manager doesn't trap exits, so boss will restart manager

    assert(countDown.await(2, TimeUnit.SECONDS))
  }

  @Test
  def supervisorShouldReceiveNotificationMessageWhenMaximumNumberOfRestartsWithinTimeRangeIsReached = {
    val countDown = new CountDownLatch(2)
    val crasher = actorOf(new CountDownActor(countDown))
    val boss = actorOf(new Actor {
      self.faultHandler = OneForOneStrategy(List(classOf[Throwable]), 1, 5000)
      protected def receive = {
        case MaximumNumberOfRestartsWithinTimeRangeReached(_, _, _, _) ⇒
          countDown.countDown()
      }
    }).start()
    boss.startLink(crasher)

    crasher ! Death(crasher, new FireWorkerException("Fire the worker!"))
    crasher ! Death(crasher, new FireWorkerException("Fire the worker!"))

    assert(countDown.await(2, TimeUnit.SECONDS))
  }
}

