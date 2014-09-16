/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import language.postfixOps
import scala.collection.immutable
import scala.concurrent.duration._
import akka.actor.Actor
import akka.actor.ActorCell
import akka.actor.ActorRef
import akka.actor.Props
import akka.stream.TimerTransformer.Scheduled
import akka.stream.testkit.AkkaSpec
import akka.testkit.TestDuration
import akka.testkit.TestKit

object TimerTransformerSpec {
  case object TestSingleTimer
  case object TestSingleTimerResubmit
  case object TestCancelTimer
  case object TestCancelTimerAck
  case object TestRepeatedTimer
  case class Tick(n: Int)

  def driverProps(probe: ActorRef): Props =
    Props(classOf[Driver], probe).withDispatcher("akka.test.stream-dispatcher")

  class Driver(probe: ActorRef) extends Actor {

    // need implicit system for dilated
    import context.system

    val tickCount = Iterator from 1

    val transformer = new TimerTransformer[Int, Int] {
      override def onNext(elem: Int): immutable.Seq[Int] = List(elem)
      override def onTimer(timerKey: Any): immutable.Seq[Int] = {
        val tick = Tick(tickCount.next())
        probe ! tick
        if (timerKey == "TestSingleTimerResubmit" && tick.n == 1)
          scheduleOnce("TestSingleTimerResubmit", 500.millis.dilated)
        else if (timerKey == "TestRepeatedTimer" && tick.n == 5)
          cancelTimer("TestRepeatedTimer")
        Nil
      }
    }

    override def preStart(): Unit = {
      super.preStart()
      transformer.start(context)
    }

    override def postStop(): Unit = {
      super.postStop()
      transformer.stop()
    }

    def receive = {
      case TestSingleTimer ⇒
        transformer.scheduleOnce("TestSingleTimer", 500.millis.dilated)
      case TestSingleTimerResubmit ⇒
        transformer.scheduleOnce("TestSingleTimerResubmit", 500.millis.dilated)
      case TestCancelTimer ⇒
        transformer.scheduleOnce("TestCancelTimer", 1.milli.dilated)
        TestKit.awaitCond(context.asInstanceOf[ActorCell].mailbox.hasMessages, 1.second.dilated)
        transformer.cancelTimer("TestCancelTimer")
        probe ! TestCancelTimerAck
        transformer.scheduleOnce("TestCancelTimer", 500.milli.dilated)
      case TestRepeatedTimer ⇒
        transformer.schedulePeriodically("TestRepeatedTimer", 100.millis.dilated)
      case s: Scheduled ⇒ transformer.onScheduled(s)
    }
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class TimerTransformerSpec extends AkkaSpec {
  import TimerTransformerSpec._

  "A TimerTransformer" must {

    "receive single-shot timer" in {
      val driver = system.actorOf(driverProps(testActor))
      within(2 seconds) {
        within(500 millis, 1 second) {
          driver ! TestSingleTimer
          expectMsg(Tick(1))
        }
        expectNoMsg(1 second)
      }
    }

    "resubmit single-shot timer" in {
      val driver = system.actorOf(driverProps(testActor))
      within(2.5 seconds) {
        within(500 millis, 1 second) {
          driver ! TestSingleTimerResubmit
          expectMsg(Tick(1))
        }
        within(1 second) {
          expectMsg(Tick(2))
        }
        expectNoMsg(1 second)
      }
    }

    "correctly cancel a named timer" in {
      val driver = system.actorOf(driverProps(testActor))
      driver ! TestCancelTimer
      within(500 millis) {
        expectMsg(TestCancelTimerAck)
      }
      within(300 millis, 1 second) {
        expectMsg(Tick(1))
      }
      expectNoMsg(1 second)
    }

    "receive and cancel a repeated timer" in {
      val driver = system.actorOf(driverProps(testActor))
      driver ! TestRepeatedTimer
      val seq = receiveWhile(2 seconds) {
        case t: Tick ⇒ t
      }
      seq should have length 5
      expectNoMsg(1 second)
    }

  }

}
