/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import java.lang.Thread.sleep

import org.scalatest.junit.JUnitSuite
import org.junit.Test
import org.scalatest.BeforeAndAfterAll
import akka.event.EventHandler
import akka.testkit.TestEvent._
import akka.testkit.EventFilter

import Actor._
import java.util.concurrent.{ TimeUnit, CountDownLatch }
import akka.config.Supervision.{ Permanent, LifeCycle, OneForOneStrategy }
import org.multiverse.api.latches.StandardLatch

class RestartStrategySpec extends JUnitSuite with BeforeAndAfterAll {

  override def beforeAll() {
    EventHandler.notify(Mute(EventFilter[Exception]("Crashing...")))
  }

  override def afterAll() {
    EventHandler.notify(UnMuteAll)
  }

  object Ping
  object Crash

  @Test
  def slaveShouldStayDeadAfterMaxRestartsWithinTimeRange = {

    val boss = actorOf(Props(new Actor {
      protected def receive = { case _ ⇒ () }
    }).withFaultHandler(OneForOneStrategy(List(classOf[Throwable]), 2, 1000)))

    val restartLatch = new StandardLatch
    val secondRestartLatch = new StandardLatch
    val countDownLatch = new CountDownLatch(3)
    val stopLatch = new StandardLatch

    val slave = actorOf(Props(new Actor {

      protected def receive = {
        case Ping  ⇒ countDownLatch.countDown()
        case Crash ⇒ throw new Exception("Crashing...")
      }
      override def postRestart(reason: Throwable) = {
        if (!restartLatch.isOpen)
          restartLatch.open
        else
          secondRestartLatch.open
      }

      override def postStop() = {
        stopLatch.open
      }
    }).withSupervisor(boss))

    slave ! Ping
    slave ! Crash
    slave ! Ping

    // test restart and post restart ping
    assert(restartLatch.tryAwait(1, TimeUnit.SECONDS))

    assert(slave.isRunning)

    // now crash again... should not restart
    slave ! Crash
    slave ! Ping

    assert(secondRestartLatch.tryAwait(1, TimeUnit.SECONDS))
    assert(countDownLatch.await(1, TimeUnit.SECONDS))

    slave ! Crash
    assert(stopLatch.tryAwait(1, TimeUnit.SECONDS))

    assert(!slave.isRunning)
  }

  @Test
  def slaveShouldBeImmortalWithoutMaxRestartsAndTimeRange = {

    val boss = actorOf(Props(new Actor {
      def receive = { case _ ⇒ () }
    }).withFaultHandler(OneForOneStrategy(List(classOf[Throwable]), None, None)))

    val countDownLatch = new CountDownLatch(100)

    val slave = actorOf(Props(new Actor {

      protected def receive = {
        case Crash ⇒ throw new Exception("Crashing...")
      }

      override def postRestart(reason: Throwable) = {
        countDownLatch.countDown()
      }
    }).withSupervisor(boss))

    (1 to 100) foreach { _ ⇒ slave ! Crash }
    assert(countDownLatch.await(120, TimeUnit.SECONDS))
    assert(slave.isRunning)
  }

  @Test
  def slaveShouldRestartAfterNumberOfCrashesNotWithinTimeRange = {

    val boss = actorOf(Props(new Actor {
      def receive = { case _ ⇒ () }
    }).withFaultHandler(OneForOneStrategy(List(classOf[Throwable]), 2, 500)))

    val restartLatch = new StandardLatch
    val secondRestartLatch = new StandardLatch
    val thirdRestartLatch = new StandardLatch
    val pingLatch = new StandardLatch
    val secondPingLatch = new StandardLatch

    val slave = actorOf(Props(new Actor {

      protected def receive = {
        case Ping ⇒
          if (!pingLatch.isOpen) pingLatch.open else secondPingLatch.open
        case Crash ⇒ throw new Exception("Crashing...")
      }
      override def postRestart(reason: Throwable) = {
        if (!restartLatch.isOpen)
          restartLatch.open
        else if (!secondRestartLatch.isOpen)
          secondRestartLatch.open
        else
          thirdRestartLatch.open
      }

      override def postStop() = {
        if (restartLatch.isOpen) {
          secondRestartLatch.open
        }
      }
    }).withSupervisor(boss))

    slave ! Ping
    slave ! Crash

    assert(restartLatch.tryAwait(1, TimeUnit.SECONDS))
    assert(pingLatch.tryAwait(1, TimeUnit.SECONDS))

    slave ! Ping
    slave ! Crash

    assert(secondRestartLatch.tryAwait(1, TimeUnit.SECONDS))
    assert(secondPingLatch.tryAwait(1, TimeUnit.SECONDS))

    // sleep to go out of the restart strategy's time range
    sleep(700L)

    // now crash again... should and post restart ping
    slave ! Crash
    slave ! Ping

    assert(thirdRestartLatch.tryAwait(1, TimeUnit.SECONDS))

    assert(slave.isRunning)
  }

  @Test
  def slaveShouldNotRestartAfterMaxRetries = {
    val boss = actorOf(Props(new Actor {
      def receive = { case _ ⇒ () }
    }).withFaultHandler(OneForOneStrategy(List(classOf[Throwable]), Some(2), None)))

    val restartLatch = new StandardLatch
    val secondRestartLatch = new StandardLatch
    val countDownLatch = new CountDownLatch(3)
    val stopLatch = new StandardLatch

    val slave = actorOf(Props(new Actor {

      protected def receive = {
        case Ping  ⇒ countDownLatch.countDown()
        case Crash ⇒ throw new Exception("Crashing...")
      }
      override def postRestart(reason: Throwable) = {
        if (!restartLatch.isOpen)
          restartLatch.open
        else
          secondRestartLatch.open
      }

      override def postStop() = {
        stopLatch.open
      }
    }).withSupervisor(boss))

    slave ! Ping
    slave ! Crash
    slave ! Ping

    // test restart and post restart ping
    assert(restartLatch.tryAwait(1, TimeUnit.SECONDS))

    assert(slave.isRunning)

    // now crash again... should not restart
    slave ! Crash
    slave ! Ping

    assert(secondRestartLatch.tryAwait(1, TimeUnit.SECONDS))
    assert(countDownLatch.await(1, TimeUnit.SECONDS))

    sleep(700L)

    slave ! Crash
    assert(stopLatch.tryAwait(1, TimeUnit.SECONDS))

    assert(!slave.isRunning)
  }

  @Test
  def slaveShouldNotRestartWithinsTimeRange = {

    val restartLatch, stopLatch, maxNoOfRestartsLatch = new StandardLatch
    val countDownLatch = new CountDownLatch(2)

    val boss = actorOf(Props(new Actor {
      def receive = { case m: MaximumNumberOfRestartsWithinTimeRangeReached ⇒ maxNoOfRestartsLatch.open }
    }).withFaultHandler(OneForOneStrategy(List(classOf[Throwable]), None, Some(1000))))

    val slave = actorOf(Props(new Actor {

      protected def receive = {
        case Ping  ⇒ countDownLatch.countDown()
        case Crash ⇒ throw new Exception("Crashing...")
      }

      override def postRestart(reason: Throwable) = {
        restartLatch.open
      }

      override def postStop() = {
        stopLatch.open
      }
    }).withSupervisor(boss))

    slave ! Ping
    slave ! Crash
    slave ! Ping

    // test restart and post restart ping
    assert(restartLatch.tryAwait(1, TimeUnit.SECONDS))

    assert(slave.isRunning)

    // now crash again... should not restart
    slave ! Crash

    // may not be running
    try {
      slave ! Ping
    } catch {
      case e: ActorInitializationException ⇒ ()
    }

    assert(countDownLatch.await(1, TimeUnit.SECONDS))

    // may not be running
    try {
      slave ! Crash
    } catch {
      case e: ActorInitializationException ⇒ ()
    }

    assert(stopLatch.tryAwait(1, TimeUnit.SECONDS))

    assert(maxNoOfRestartsLatch.tryAwait(1, TimeUnit.SECONDS))

    assert(!slave.isRunning)
  }
}

