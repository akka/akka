package akka.stream.scaladsl

import akka.actor.ActorRef
import akka.stream.ActorMaterializer
import akka.stream.impl.fusing.GraphStages.SimpleLinearGraphStage
import akka.stream.stage.{ OutHandler, AsyncCallback, InHandler }
import akka.stream.testkit.{ AkkaSpec, TestPublisher }
import akka.testkit.TestDuration

import scala.concurrent.Promise
import scala.concurrent.duration._

import akka.stream.testkit._
import akka.stream.testkit.Utils._

object GraphStageTimersSpec {
  case object TestSingleTimer
  case object TestSingleTimerResubmit
  case object TestCancelTimer
  case object TestCancelTimerAck
  case object TestRepeatedTimer
  case class Tick(n: Int)

  class SideChannel {
    @volatile var asyncCallback: AsyncCallback[Any] = _
    @volatile var stopPromise: Promise[Unit] = _

    def isReady: Boolean = asyncCallback ne null
    def !(msg: Any) = asyncCallback.invoke(msg)

    def stopStage(): Unit = stopPromise.trySuccess(())
  }

}

class GraphStageTimersSpec extends AkkaSpec {
  import GraphStageTimersSpec._

  implicit val mat = ActorMaterializer()

  class TestStage(probe: ActorRef, sideChannel: SideChannel) extends SimpleLinearGraphStage[Int] {
    override def createLogic = new SimpleLinearStageLogic {
      val tickCount = Iterator from 1

      setHandler(in, new InHandler {
        override def onPush() = push(out, grab(in))
      })

      override def preStart() = {
        sideChannel.asyncCallback = getAsyncCallback(onTestEvent)
      }

      override protected def onTimer(timerKey: Any) = {
        val tick = Tick(tickCount.next())
        probe ! tick
        if (timerKey == "TestSingleTimerResubmit" && tick.n == 1)
          scheduleOnce("TestSingleTimerResubmit", 500.millis.dilated)
        else if (timerKey == "TestRepeatedTimer" && tick.n == 5)
          cancelTimer("TestRepeatedTimer")
        Nil
      }

      private def onTestEvent(event: Any): Unit = event match {
        case TestSingleTimer ⇒
          scheduleOnce("TestSingleTimer", 500.millis.dilated)
        case TestSingleTimerResubmit ⇒
          scheduleOnce("TestSingleTimerResubmit", 500.millis.dilated)
        case TestCancelTimer ⇒
          scheduleOnce("TestCancelTimer", 1.milli.dilated)
          // Likely in mailbox but we cannot guarantee
          cancelTimer("TestCancelTimer")
          probe ! TestCancelTimerAck
          scheduleOnce("TestCancelTimer", 500.milli.dilated)
        case TestRepeatedTimer ⇒
          schedulePeriodically("TestRepeatedTimer", 100.millis.dilated)
      }
    }
  }

  "GraphStage timer support" must {

    def setupIsolatedStage: SideChannel = {
      val channel = new SideChannel
      val stopPromise = Source.lazyEmpty[Int].via(new TestStage(testActor, channel)).to(Sink.ignore).run()
      channel.stopPromise = stopPromise
      awaitCond(channel.isReady)
      channel
    }

    "receive single-shot timer" in {
      val driver = setupIsolatedStage

      within(2.seconds) {
        within(500.millis, 1.second) {
          driver ! TestSingleTimer
          expectMsg(Tick(1))
        }
        expectNoMsg(1.second)
      }

      driver.stopStage()
    }

    "resubmit single-shot timer" in {
      val driver = setupIsolatedStage

      within(2.5.seconds) {
        within(500.millis, 1.second) {
          driver ! TestSingleTimerResubmit
          expectMsg(Tick(1))
        }
        within(1.second) {
          expectMsg(Tick(2))
        }
        expectNoMsg(1.second)
      }

      driver.stopStage()
    }

    "correctly cancel a named timer" in {
      val driver = setupIsolatedStage

      driver ! TestCancelTimer
      within(500.millis) {
        expectMsg(TestCancelTimerAck)
      }
      within(300.millis, 1.second) {
        expectMsg(Tick(1))
      }
      expectNoMsg(1.second)

      driver.stopStage()
    }

    "receive and cancel a repeated timer" in {
      val driver = setupIsolatedStage

      driver ! TestRepeatedTimer
      val seq = receiveWhile(2.seconds) {
        case t: Tick ⇒ t
      }
      seq should have length 5
      expectNoMsg(1.second)

      driver.stopStage()
    }

    class TestStage2 extends SimpleLinearGraphStage[Int] {
      override def createLogic = new SimpleLinearStageLogic {
        var tickCount = 0

        override def preStart(): Unit = schedulePeriodically("tick", 100.millis)

        setHandler(out, new OutHandler {
          override def onPull() = () // Do nothing
          override def onDownstreamFinish() = completeStage()
        })

        setHandler(in, new InHandler {
          override def onPush() = () // Do nothing
          override def onUpstreamFinish() = completeStage()
          override def onUpstreamFailure(ex: Throwable) = failStage(ex)
        })

        override def onTimer(timerKey: Any) = {
          tickCount += 1
          if (isAvailable(out)) push(out, tickCount)
          if (tickCount == 3) cancelTimer("tick")
        }

      }
    }

    "produce scheduled ticks as expected" in assertAllStagesStopped {
      val upstream = TestPublisher.probe[Int]()
      val downstream = TestSubscriber.probe[Int]()

      Source(upstream).via(new TestStage2).runWith(Sink(downstream))

      downstream.request(5)
      downstream.expectNext(1)
      downstream.expectNext(2)
      downstream.expectNext(3)

      downstream.expectNoMsg(1.second)

      upstream.sendComplete()
      downstream.expectComplete()
    }

    "propagate error if onTimer throws an exception" in assertAllStagesStopped {
      val exception = TE("Expected exception to the rule")
      val upstream = TestPublisher.probe[Int]()
      val downstream = TestSubscriber.probe[Int]()

      Source(upstream).via(new SimpleLinearGraphStage[Int] {
        override def createLogic = new SimpleLinearStageLogic {
          override def preStart(): Unit = scheduleOnce("tick", 100.millis)

          setHandler(in, new InHandler {
            override def onPush() = () // Ingore
          })

          override def onTimer(timerKey: Any) = throw exception
        }
      }).runWith(Sink(downstream))

      downstream.request(1)
      downstream.expectError(exception)
    }

  }

}
