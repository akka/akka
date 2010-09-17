/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.actor

import org.scalatest.junit.JUnitSuite
import org.junit.Test

import Actor._
import se.scalablesolutions.akka.config.OneForOneStrategy
import java.util.concurrent.{TimeUnit, CountDownLatch}
import se.scalablesolutions.akka.config.ScalaConfig.{Permanent, LifeCycle}
import org.multiverse.api.latches.StandardLatch

class RestartStrategySpec extends JUnitSuite {

  object Ping
  object Crash

  @Test
  def slaveShouldStayDeadAfterMaxRestarts = {

    val boss = actorOf(new Actor{
      self.trapExit = List(classOf[Throwable])
      self.faultHandler = Some(OneForOneStrategy(1, 1000))
      protected def receive = { case _ => () }
    }).start

    val restartLatch = new StandardLatch
    val secondRestartLatch = new StandardLatch
    val countDownLatch = new CountDownLatch(2)


    val slave = actorOf(new Actor{

      protected def receive = {
        case Ping => countDownLatch.countDown
        case Crash => throw new Exception("Crashing...")
      }
      override def postRestart(reason: Throwable) = {
        restartLatch.open
      }

      override def postStop = {
        if (restartLatch.isOpen) {
          secondRestartLatch.open
        }
      }
    })
    boss.startLink(slave)

    slave ! Ping
    slave ! Crash
    slave ! Ping

    // test restart and post restart ping
    assert(restartLatch.tryAwait(1, TimeUnit.SECONDS))
    assert(countDownLatch.await(1, TimeUnit.SECONDS))

    // now crash again... should not restart
    slave ! Crash

    assert(secondRestartLatch.tryAwait(1, TimeUnit.SECONDS))
    val exceptionLatch = new StandardLatch
    try {
      slave ! Ping // this should fail
    } catch {
      case e => exceptionLatch.open // expected here
    }
    assert(exceptionLatch.tryAwait(1, TimeUnit.SECONDS))
  }

  @Test
  def slaveShouldBeImmortalWithoutMaxRestarts = {

    val boss = actorOf(new Actor{
      self.trapExit = List(classOf[Throwable])
      self.faultHandler = Some(OneForOneStrategy(None, None))
      protected def receive = { case _ => () }
    }).start

    val countDownLatch = new CountDownLatch(100)

    val slave = actorOf(new Actor{

      protected def receive = {
        case Crash => throw new Exception("Crashing...")
      }

      override def postRestart(reason: Throwable) = {
        countDownLatch.countDown
      }
    })

    boss.startLink(slave)
    (1 to 100) foreach { _ => slave ! Crash }
    assert(countDownLatch.await(120, TimeUnit.SECONDS))
  }
}

