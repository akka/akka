/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import java.lang.Thread.sleep
import org.scalatest.BeforeAndAfterAll
import akka.dispatch.Await
import akka.testkit.TestEvent._
import akka.testkit.EventFilter
import java.util.concurrent.{ TimeUnit, CountDownLatch }
import akka.testkit.AkkaSpec
import akka.testkit.DefaultTimeout
import akka.testkit.TestLatch
import scala.util.duration._
import scala.util.Duration
import akka.pattern.ask

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class RestartStrategySpec extends AkkaSpec with DefaultTimeout {

  override def atStartup {
    system.eventStream.publish(Mute(EventFilter[Exception]("Crashing...")))
  }

  object Ping
  object Crash

  "A RestartStrategy" must {

    "ensure that slave stays dead after max restarts within time range" in {
      val boss = system.actorOf(Props(new Supervisor(
        OneForOneStrategy(maxNrOfRetries = 2, withinTimeRange = 1 second)(List(classOf[Throwable])))))

      val restartLatch = new TestLatch
      val secondRestartLatch = new TestLatch
      val countDownLatch = new TestLatch(3)
      val stopLatch = new TestLatch

      val slaveProps = Props(new Actor {

        protected def receive = {
          case Ping  ⇒ countDownLatch.countDown()
          case Crash ⇒ throw new Exception("Crashing...")
        }

        override def postRestart(reason: Throwable) = {
          if (!restartLatch.isOpen)
            restartLatch.open()
          else
            secondRestartLatch.open()
        }

        override def postStop() = {
          stopLatch.open()
        }
      })
      val slave = Await.result((boss ? slaveProps).mapTo[ActorRef], timeout.duration)

      slave ! Ping
      slave ! Crash
      slave ! Ping

      // test restart and post restart ping
      Await.ready(restartLatch, 10 seconds)

      // now crash again... should not restart
      slave ! Crash
      slave ! Ping

      Await.ready(secondRestartLatch, 10 seconds)
      Await.ready(countDownLatch, 10 seconds)

      slave ! Crash
      Await.ready(stopLatch, 10 seconds)
    }

    "ensure that slave is immortal without max restarts and time range" in {
      val boss = system.actorOf(Props(new Supervisor(OneForOneStrategy()(List(classOf[Throwable])))))

      val countDownLatch = new TestLatch(100)

      val slaveProps = Props(new Actor {

        protected def receive = {
          case Crash ⇒ throw new Exception("Crashing...")
        }

        override def postRestart(reason: Throwable) = {
          countDownLatch.countDown()
        }
      })
      val slave = Await.result((boss ? slaveProps).mapTo[ActorRef], timeout.duration)

      (1 to 100) foreach { _ ⇒ slave ! Crash }
      Await.ready(countDownLatch, 2 minutes)
      assert(!slave.isTerminated)
    }

    "ensure that slave restarts after number of crashes not within time range" in {
      val boss = system.actorOf(Props(new Supervisor(
        OneForOneStrategy(maxNrOfRetries = 2, withinTimeRange = 500 millis)(List(classOf[Throwable])))))

      val restartLatch = new TestLatch
      val secondRestartLatch = new TestLatch
      val thirdRestartLatch = new TestLatch
      val pingLatch = new TestLatch
      val secondPingLatch = new TestLatch

      val slaveProps = Props(new Actor {

        protected def receive = {
          case Ping ⇒
            if (!pingLatch.isOpen) pingLatch.open else secondPingLatch.open
          case Crash ⇒ throw new Exception("Crashing...")
        }
        override def postRestart(reason: Throwable) = {
          if (!restartLatch.isOpen)
            restartLatch.open()
          else if (!secondRestartLatch.isOpen)
            secondRestartLatch.open()
          else
            thirdRestartLatch.open()
        }

        override def postStop() = {
          if (restartLatch.isOpen) {
            secondRestartLatch.open()
          }
        }
      })
      val slave = Await.result((boss ? slaveProps).mapTo[ActorRef], timeout.duration)

      slave ! Ping
      slave ! Crash

      Await.ready(restartLatch, 10 seconds)
      Await.ready(pingLatch, 10 seconds)

      slave ! Ping
      slave ! Crash

      Await.ready(secondRestartLatch, 10 seconds)
      Await.ready(secondPingLatch, 10 seconds)

      // sleep to go out of the restart strategy's time range
      sleep(700L)

      // now crash again... should and post restart ping
      slave ! Crash
      slave ! Ping

      Await.ready(thirdRestartLatch, 1 second)

      assert(!slave.isTerminated)
    }

    "ensure that slave is not restarted after max retries" in {
      val boss = system.actorOf(Props(new Supervisor(OneForOneStrategy(maxNrOfRetries = 2)(List(classOf[Throwable])))))

      val restartLatch = new TestLatch
      val secondRestartLatch = new TestLatch
      val countDownLatch = new TestLatch(3)
      val stopLatch = new TestLatch

      val slaveProps = Props(new Actor {

        protected def receive = {
          case Ping  ⇒ countDownLatch.countDown()
          case Crash ⇒ throw new Exception("Crashing...")
        }
        override def postRestart(reason: Throwable) = {
          if (!restartLatch.isOpen)
            restartLatch.open()
          else
            secondRestartLatch.open()
        }

        override def postStop() = {
          stopLatch.open()
        }
      })
      val slave = Await.result((boss ? slaveProps).mapTo[ActorRef], timeout.duration)

      slave ! Ping
      slave ! Crash
      slave ! Ping

      // test restart and post restart ping
      Await.ready(restartLatch, 10 seconds)

      assert(!slave.isTerminated)

      // now crash again... should not restart
      slave ! Crash
      slave ! Ping

      Await.ready(secondRestartLatch, 10 seconds)
      Await.ready(countDownLatch, 10 seconds)

      sleep(700L)

      slave ! Crash
      Await.ready(stopLatch, 10 seconds)
      sleep(500L)
      assert(slave.isTerminated)
    }

    "ensure that slave is not restarted within time range" in {
      val restartLatch, stopLatch, maxNoOfRestartsLatch = new TestLatch
      val countDownLatch = new TestLatch(2)

      val boss = system.actorOf(Props(new Actor {
        override val supervisorStrategy = OneForOneStrategy(withinTimeRange = 1 second)(List(classOf[Throwable]))
        def receive = {
          case p: Props      ⇒ sender ! context.watch(context.actorOf(p))
          case t: Terminated ⇒ maxNoOfRestartsLatch.open()
        }
      }))

      val slaveProps = Props(new Actor {

        protected def receive = {
          case Ping  ⇒ countDownLatch.countDown()
          case Crash ⇒ throw new Exception("Crashing...")
        }

        override def postRestart(reason: Throwable) = {
          restartLatch.open()
        }

        override def postStop() = {
          stopLatch.open()
        }
      })
      val slave = Await.result((boss ? slaveProps).mapTo[ActorRef], timeout.duration)

      slave ! Ping
      slave ! Crash
      slave ! Ping

      // test restart and post restart ping
      Await.ready(restartLatch, 10 seconds)

      assert(!slave.isTerminated)

      // now crash again... should not restart
      slave ! Crash

      // may not be running
      slave ! Ping
      Await.ready(countDownLatch, 10 seconds)

      // may not be running
      slave ! Crash

      Await.ready(stopLatch, 10 seconds)

      Await.ready(maxNoOfRestartsLatch, 10 seconds)
      sleep(500L)
      assert(slave.isTerminated)
    }
  }
}

