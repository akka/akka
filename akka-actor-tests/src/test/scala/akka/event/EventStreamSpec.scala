/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.event

import language.postfixOps

import akka.testkit.AkkaSpec
import scala.concurrent.util.duration._
import akka.actor.{ Actor, ActorRef, ActorSystemImpl, ActorSystem, Props, UnhandledMessage }
import com.typesafe.config.ConfigFactory
import scala.collection.JavaConverters._
import akka.event.Logging.InitializeLogger
import akka.pattern.gracefulStop

object EventStreamSpec {

  val config = ConfigFactory.parseString("""
      akka {
        stdout-loglevel = WARNING
        loglevel = INFO
        event-handlers = ["akka.event.EventStreamSpec$MyLog", "%s"]
      }
      """.format(Logging.StandardOutLogger.getClass.getName))

  val configUnhandled = ConfigFactory.parseString("""
      akka {
        stdout-loglevel = WARNING
        loglevel = DEBUG
        actor.debug.unhandled = on
      }
      """)

  case class M(i: Int)

  case class SetTarget(ref: ActorRef)

  class MyLog extends Actor {
    var dst: ActorRef = context.system.deadLetters
    def receive = {
      case Logging.InitializeLogger(bus) ⇒
        bus.subscribe(context.self, classOf[SetTarget])
        bus.subscribe(context.self, classOf[UnhandledMessage])
        sender ! Logging.LoggerInitialized
      case SetTarget(ref)      ⇒ dst = ref; dst ! "OK"
      case e: Logging.LogEvent ⇒ dst ! e
      case u: UnhandledMessage ⇒ dst ! u
    }
  }

  // class hierarchy for subchannel test
  class A
  class B1 extends A
  class B2 extends A
  class C extends B1
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class EventStreamSpec extends AkkaSpec(EventStreamSpec.config) {

  import EventStreamSpec._

  val impl = system.asInstanceOf[ActorSystemImpl]

  "An EventStream" must {

    "manage subscriptions" in {
      val bus = new EventStream(true)
      bus.subscribe(testActor, classOf[M])
      bus.publish(M(42))
      within(1 second) {
        expectMsg(M(42))
        bus.unsubscribe(testActor)
        bus.publish(M(13))
        expectNoMsg
      }
    }

    "not allow null as subscriber" in {
      val bus = new EventStream(true)
      intercept[IllegalArgumentException] { bus.subscribe(null, classOf[M]) }.getMessage must be("subscriber is null")
    }

    "not allow null as unsubscriber" in {
      val bus = new EventStream(true)
      intercept[IllegalArgumentException] { bus.unsubscribe(null, classOf[M]) }.getMessage must be("subscriber is null")
      intercept[IllegalArgumentException] { bus.unsubscribe(null) }.getMessage must be("subscriber is null")
    }

    "be able to log unhandled messages" in {
      val sys = ActorSystem("EventStreamSpecUnhandled", configUnhandled)
      try {
        sys.eventStream.subscribe(testActor, classOf[AnyRef])
        val m = UnhandledMessage(42, sys.deadLetters, sys.deadLetters)
        sys.eventStream.publish(m)
        expectMsgAllOf(m, Logging.Debug(sys.deadLetters.path.toString, sys.deadLetters.getClass, "unhandled message from " + sys.deadLetters + ": 42"))
        sys.eventStream.unsubscribe(testActor)
      } finally {
        sys.shutdown()
      }
    }

    "manage log levels" in {
      val bus = new EventStream(false)
      bus.startDefaultLoggers(impl)
      bus.publish(SetTarget(testActor))
      expectMsg("OK")
      within(2 seconds) {
        import Logging._
        verifyLevel(bus, InfoLevel)
        bus.setLogLevel(WarningLevel)
        verifyLevel(bus, WarningLevel)
        bus.setLogLevel(DebugLevel)
        verifyLevel(bus, DebugLevel)
        bus.setLogLevel(ErrorLevel)
        verifyLevel(bus, ErrorLevel)
      }
    }

    "manage sub-channels" in {
      val a = new A
      val b1 = new B1
      val b2 = new B2
      val c = new C
      val bus = new EventStream(false)
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
    val allmsg = Seq(Debug("", null, "debug"), Info("", null, "info"), Warning("", null, "warning"), Error("", null, "error"))
    val msg = allmsg filter (_.level <= level)
    allmsg foreach bus.publish
    msg foreach (x ⇒ expectMsg(x))
  }

}