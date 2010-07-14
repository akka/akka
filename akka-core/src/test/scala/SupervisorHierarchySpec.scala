/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.actor

import org.scalatest.junit.JUnitSuite
import org.junit.Test
import java.lang.Throwable
import Actor._
import se.scalablesolutions.akka.config.OneForOneStrategy
import java.util.concurrent.{TimeUnit, CountDownLatch}

class SupervisorHierarchySpec extends JUnitSuite {

  @Test
  def killWorkerShouldRestartMangerAndOtherWorkers = {
    val countDown = new CountDownLatch(4)

    val workerOne = actorOf(new CountDownActor(countDown))
    val workerTwo = actorOf(new CountDownActor(countDown))
    val workerThree = actorOf(new CountDownActor( countDown))

    val boss = actorOf(new Actor{
      self.trapExit = List(classOf[Throwable])
      self.faultHandler = Some(OneForOneStrategy(5, 1000))

      protected def receive = { case _ => () }
    }).start

    val manager = actorOf(new CountDownActor(countDown))
    boss.startLink(manager)

    manager.startLink(workerOne)
    manager.startLink(workerTwo)
    manager.startLink(workerThree)

    workerOne ! Exit(workerOne, new RuntimeException("Fire the worker!"))

    // manager + all workers should be restarted by only killing a worker
    // manager doesn't trap exits, so boss will restart manager

    assert(countDown.await(4, TimeUnit.SECONDS))
  }

  class CountDownActor(countDown: CountDownLatch) extends Actor {

    protected def receive = { case _ => () }

    override def postRestart(reason: Throwable) = countDown.countDown
  }
}

