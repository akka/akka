/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.event

import akka.testkit.AkkaSpec
import akka.config.Configuration
import akka.util.duration._
import akka.actor.{ Actor, ActorRef }

object MainBusSpec {
  case class M(i: Int)

  case class SetTarget(ref: ActorRef)

  class MyLog extends Actor {
    var dst: ActorRef = app.deadLetters
    def receive = {
      case Logging.InitializeLogger(bus) ⇒ bus.subscribe(context.self, classOf[SetTarget])
      case SetTarget(ref)                ⇒ dst = ref
      case e: Logging.LogEvent           ⇒ dst ! e
    }
  }

  // class hierarchy for subchannel test
  class A
  class B1 extends A
  class B2 extends A
  class C extends B1
}

class MainBusSpec extends AkkaSpec(Configuration(
  "akka.stdout-loglevel" -> "WARNING",
  "akka.loglevel" -> "INFO",
  "akka.event-handlers" -> Seq("akka.event.MainBusSpec$MyLog", Logging.StandardOutLoggerName))) {

  import MainBusSpec._

  "A MainBus" must {

    "manage subscriptions" in {
      val bus = new MainBus(true)
      bus.start(app)
      bus.subscribe(testActor, classOf[M])
      bus.publish(M(42))
      within(1 second) {
        expectMsg(M(42))
        bus.unsubscribe(testActor)
        bus.publish(M(13))
        expectNoMsg
      }
    }

    "manage log levels" in {
      val bus = new MainBus(false)
      bus.start(app)
      bus.startDefaultLoggers(app, app.AkkaConfig)
      bus.publish(SetTarget(testActor))
      within(1 second) {
        import Logging._
        verifyLevel(bus, InfoLevel)
        bus.logLevel = WarningLevel
        verifyLevel(bus, WarningLevel)
        bus.logLevel = DebugLevel
        verifyLevel(bus, DebugLevel)
        bus.logLevel = ErrorLevel
        verifyLevel(bus, ErrorLevel)
      }
    }

    "manage sub-channels" in {
      val a = new A
      val b1 = new B1
      val b2 = new B2
      val c = new C
      val bus = new MainBus(false)
      bus.start(app)
      within(2 seconds) {
        bus.subscribe(testActor, classOf[B2]) === true
        bus.publish(c)
        bus.publish(b2)
        expectMsg(b2)
        bus.subscribe(testActor, classOf[A]) === true
        bus.publish(c)
        expectMsg(c)
        bus.publish(b1)
        expectMsg(b1)
        bus.unsubscribe(testActor, classOf[B1]) === true
        bus.publish(c)
        bus.publish(b2)
        bus.publish(a)
        expectMsg(b2)
        expectMsg(a)
        expectNoMsg
      }
    }

  }

  private def verifyLevel(bus: LoggingBus, level: Logging.LogLevel) {
    import Logging._
    val allmsg = Seq(Debug(this, "debug"), Info(this, "info"), Warning(this, "warning"), Error(this, "error"))
    val msg = allmsg filter (_.level <= level)
    allmsg foreach bus.publish
    msg foreach (x ⇒ expectMsg(x))
  }

}