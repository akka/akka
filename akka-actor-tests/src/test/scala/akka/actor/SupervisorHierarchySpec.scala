/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import org.scalatest.junit.JUnitSuite
import org.junit.Test

import Actor._
import akka.config.Supervision.OneForOnePermanentStrategy
import akka.testkit._

import java.util.concurrent.{ TimeUnit, CountDownLatch }

object SupervisorHierarchySpec {
  class FireWorkerException(msg: String) extends Exception(msg)

  class CountDownActor(countDown: CountDownLatch) extends Actor {
    protected def receive = { case _ ⇒ }
    override def postRestart(reason: Throwable) = countDown.countDown()
  }
}

class SupervisorHierarchySpec extends JUnitSuite {
  import SupervisorHierarchySpec._

  @Test
  def killWorkerShouldRestartMangerAndOtherWorkers = {
    val countDown = new CountDownLatch(4)

    val boss = actorOf(Props(self ⇒ { case _ ⇒ }).withFaultHandler(OneForOnePermanentStrategy(List(classOf[Throwable]), 5, 1000)))

    val manager = actorOf(Props(new CountDownActor(countDown)).withFaultHandler(OneForOnePermanentStrategy(List(), None, None)).withSupervisor(boss))

    val workerOne, workerTwo, workerThree = actorOf(Props(new CountDownActor(countDown)).withSupervisor(manager))

    filterException[ActorKilledException] {
      workerOne ! Kill

      // manager + all workers should be restarted by only killing a worker
      // manager doesn't trap exits, so boss will restart manager

      assert(countDown.await(2, TimeUnit.SECONDS))
    }
  }

  @Test
  def supervisorShouldReceiveNotificationMessageWhenMaximumNumberOfRestartsWithinTimeRangeIsReached = {
    val countDownMessages = new CountDownLatch(1)
    val countDownMax = new CountDownLatch(1)
    val boss = actorOf(Props(new Actor {
      protected def receive = {
        case MaximumNumberOfRestartsWithinTimeRangeReached(_, _, _, _) ⇒ countDownMax.countDown()
      }
    }).withFaultHandler(OneForOnePermanentStrategy(List(classOf[Throwable]), 1, 5000)))

    val crasher = actorOf(Props(new CountDownActor(countDownMessages)).withSupervisor(boss))

    filterException[ActorKilledException] {
      crasher ! Kill
      crasher ! Kill

      assert(countDownMessages.await(2, TimeUnit.SECONDS))
      assert(countDownMax.await(2, TimeUnit.SECONDS))
    }
  }
}

