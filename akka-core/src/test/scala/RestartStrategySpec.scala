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
    val firstCountDown = new CountDownLatch(2)
    val secondCountDown = new CountDownLatch(2)


    val slave = actorOf(new Actor{
      self.lifeCycle = Some(LifeCycle(Permanent))

      protected def receive = {
        case Ping => {
          log.info("png")
          if (firstCountDown.getCount > 0) {
            firstCountDown.countDown
          } else {
            secondCountDown.countDown
          }
        }
        case Crash => throw new Exception("Crashing...")
      }
      override def postRestart(reason: Throwable) = restartLatch.open
    })
    boss.startLink(slave)

    slave ! Ping
    slave ! Crash
    slave ! Ping

    // test restart and post restart ping
    assert(restartLatch.tryAwait(1, TimeUnit.SECONDS))
    assert(firstCountDown.await(1, TimeUnit.SECONDS))

    // now crash again... should not restart
    slave ! Crash

    slave ! Ping // this should fail
    slave ! Ping // this should fail
    slave ! Ping // this should fail
    slave ! Ping // this should fail
    assert(secondCountDown.await(2, TimeUnit.SECONDS) == false) // should not hold
  }
}

