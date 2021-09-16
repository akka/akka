/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.event

import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory

import akka.actor._
import akka.testkit.{ AkkaSpec, GHExcludeTest, TestProbe }

object EventStreamSpec {

  val config = ConfigFactory.parseString("""
      akka {
        stdout-loglevel = WARNING
        loglevel = INFO
        loggers = ["akka.event.EventStreamSpec$MyLog", "%s"]
      }
      """.format(Logging.StandardOutLogger.getClass.getName))

  val configUnhandled = ConfigFactory.parseString("""
      akka {
        stdout-loglevel = WARNING
        loglevel = WARNING
        actor.debug.unhandled = on
        log-dead-letters = off
      }
      """)

  val configUnhandledWithDebug =
    ConfigFactory.parseString("akka.actor.debug.event-stream = on").withFallback(configUnhandled)

  final case class M(i: Int)

  final case class SetTarget(ref: ActorRef)

  class MyLog extends Actor {
    var dst: ActorRef = context.system.deadLetters
    def receive = {
      case Logging.InitializeLogger(bus) =>
        bus.subscribe(context.self, classOf[SetTarget])
        bus.subscribe(context.self, classOf[UnhandledMessage])
        sender() ! Logging.LoggerInitialized
      case SetTarget(ref)      => { dst = ref; dst ! "OK" }
      case e: Logging.LogEvent => dst ! e
      case u: UnhandledMessage => dst ! u
    }
  }

  // class hierarchy for subchannel test
  class A
  class B2 extends A
  class B3 extends A
  class C extends B2

  trait T
  trait AT extends T
  trait ATT extends AT
  trait BT extends T
  trait BTT extends BT
  class CC
  class CCATBT extends CC with ATT with BTT
}

class EventStreamSpec extends AkkaSpec(EventStreamSpec.config) {

  import EventStreamSpec._

  val impl = system.asInstanceOf[ActorSystemImpl]

  "An EventStream" must {

    "manage subscriptions" taggedAs GHExcludeTest in {
      //#event-bus-start-unsubscriber-scala
      val bus = new EventStream(system, true)
      bus.startUnsubscriber()
      //#event-bus-start-unsubscriber-scala

      bus.subscribe(testActor, classOf[M])
      // subscription is async
      var n = 41
      awaitAssert {
        n += 1
        bus.publish(M(n))
        expectMsg(M(n))
      }
      bus.unsubscribe(testActor)
      // unsubscription is async
      awaitAssert {
        n += 1
        bus.publish(M(n))
        expectNoMessage()
      }
    }

    "not allow null as subscriber" in {
      val bus = new EventStream(system, true)
      intercept[IllegalArgumentException] { bus.subscribe(null, classOf[M]) }.getMessage should ===(
        "subscriber is null")
    }

    "not allow null as unsubscriber" in {
      val bus = new EventStream(system, true)
      intercept[IllegalArgumentException] { bus.unsubscribe(null, classOf[M]) }.getMessage should ===(
        "subscriber is null")
      intercept[IllegalArgumentException] { bus.unsubscribe(null) }.getMessage should ===("subscriber is null")
    }

    "be able to log unhandled messages" taggedAs GHExcludeTest in {
      val sys = ActorSystem("EventStreamSpecUnhandled", configUnhandled)
      try {
        sys.eventStream.subscribe(testActor, classOf[AnyRef])
        // subscription is async
        var n = 41
        awaitAssert {
          n += 1
          val m = UnhandledMessage(n, sys.deadLetters, sys.deadLetters)
          sys.eventStream.publish(m)
          expectMsgAllOf(
            m,
            Logging.Debug(
              sys.deadLetters.path.toString,
              sys.deadLetters.getClass,
              s"unhandled message from ${sys.deadLetters}: $n"))
        }
        sys.eventStream.unsubscribe(testActor)
      } finally {
        shutdown(sys)
      }
    }

    "manage log levels" in {
      val bus = new EventStream(system, false)
      bus.startDefaultLoggers(impl)
      bus.publish(SetTarget(testActor))
      expectMsg("OK")
      within(2.seconds) {
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

    // Excluded on GH Actions: https://github.com/akka/akka/issues/30675

    "manage sub-channels using classes" taggedAs GHExcludeTest in {
      val a = new A
      val b1 = new B2
      val b2 = new B3
      val c = new C
      val bus = new EventStream(system, false)
      within(2.seconds) {
        bus.subscribe(testActor, classOf[B3]) should ===(true)
        // subscription is async
        awaitAssert {
          bus.publish(c)
          bus.publish(b2)
          expectMsg(b2)
        }
        bus.subscribe(testActor, classOf[A]) should ===(true)
        awaitAssert {
          bus.publish(c)
          expectMsg(c)
        }
        bus.publish(b1)
        expectMsg(b1)
        bus.unsubscribe(testActor, classOf[B2]) should ===(true)
        awaitAssert {
          bus.publish(c)
          bus.publish(b2)
          bus.publish(a)
          expectMsg(b2)
          expectMsg(a)
          expectNoMessage()
        }
      }
    }

    "manage sub-channels using classes and traits (update on subscribe)" taggedAs GHExcludeTest in {
      val es = new EventStream(system, false)
      val tm1 = new CC
      val tm2 = new CCATBT
      val a1, a2, a3, a4 = TestProbe()

      es.subscribe(a1.ref, classOf[AT]) should ===(true)
      es.subscribe(a2.ref, classOf[BT]) should ===(true)
      es.subscribe(a3.ref, classOf[CC]) should ===(true)
      es.subscribe(a4.ref, classOf[CCATBT]) should ===(true)
      // subscription is async
      awaitAssert {
        es.publish(tm1)
        es.publish(tm2)
        (a1.expectMsgType[AT]: AT) should ===(tm2)
        (a2.expectMsgType[BT]: BT) should ===(tm2)
        (a3.expectMsgType[CC]: CC) should ===(tm1)
        (a3.expectMsgType[CC]: CC) should ===(tm2)
        (a4.expectMsgType[CCATBT]: CCATBT) should ===(tm2)
      }
      es.unsubscribe(a1.ref, classOf[AT]) should ===(true)
      es.unsubscribe(a2.ref, classOf[BT]) should ===(true)
      es.unsubscribe(a3.ref, classOf[CC]) should ===(true)
      es.unsubscribe(a4.ref, classOf[CCATBT]) should ===(true)
    }

    "manage sub-channels using classes and traits (update on unsubscribe)" taggedAs GHExcludeTest in {
      val es = new EventStream(system, false)
      val tm1 = new CC
      val tm2 = new CCATBT
      val a1, a2, a3, a4 = TestProbe()

      es.subscribe(a1.ref, classOf[AT]) should ===(true)
      es.subscribe(a2.ref, classOf[BT]) should ===(true)
      es.subscribe(a3.ref, classOf[CC]) should ===(true)
      es.subscribe(a4.ref, classOf[CCATBT]) should ===(true)
      es.unsubscribe(a3.ref, classOf[CC]) should ===(true)
      // subscription and unsubscription is async
      awaitAssert {
        es.publish(tm1)
        es.publish(tm2)
        (a1.expectMsgType[AT]: AT) should ===(tm2)
        (a2.expectMsgType[BT]: BT) should ===(tm2)
        a3.expectNoMessage(1.second)
        (a4.expectMsgType[CCATBT]: CCATBT) should ===(tm2)
      }
      es.unsubscribe(a1.ref, classOf[AT]) should ===(true)
      es.unsubscribe(a2.ref, classOf[BT]) should ===(true)
      es.unsubscribe(a4.ref, classOf[CCATBT]) should ===(true)
    }

    "manage sub-channels using classes and traits (update on unsubscribe all)" taggedAs GHExcludeTest in {
      val es = new EventStream(system, false)
      val tm1 = new CC
      val tm2 = new CCATBT
      val a1, a2, a3, a4 = TestProbe()

      es.subscribe(a1.ref, classOf[AT]) should ===(true)
      es.subscribe(a2.ref, classOf[BT]) should ===(true)
      es.subscribe(a3.ref, classOf[CC]) should ===(true)
      es.subscribe(a4.ref, classOf[CCATBT]) should ===(true)
      // subscription and unsubscription is async
      awaitAssert {
        es.unsubscribe(a3.ref)
        es.publish(tm1)
        es.publish(tm2)
        (a1.expectMsgType[AT]: AT) should ===(tm2)
        (a2.expectMsgType[BT]: BT) should ===(tm2)
        a3.expectNoMessage(1.second)
        (a4.expectMsgType[CCATBT]: CCATBT) should ===(tm2)
      }
      es.unsubscribe(a1.ref, classOf[AT]) should ===(true)
      es.unsubscribe(a2.ref, classOf[BT]) should ===(true)
      es.unsubscribe(a4.ref, classOf[CCATBT]) should ===(true)
    }

    "manage sub-channels using classes and traits (update on publish)" taggedAs GHExcludeTest in {
      val es = new EventStream(system, false)
      val tm1 = new CC
      val tm2 = new CCATBT
      val a1, a2 = TestProbe()

      es.subscribe(a1.ref, classOf[AT]) should ===(true)
      es.subscribe(a2.ref, classOf[BT]) should ===(true)
      // subscription and unsubscription is async
      awaitAssert {
        es.publish(tm1)
        es.publish(tm2)
        (a1.expectMsgType[AT]: AT) should ===(tm2)
        (a2.expectMsgType[BT]: BT) should ===(tm2)
      }
      es.unsubscribe(a1.ref, classOf[AT]) should ===(true)
      es.unsubscribe(a2.ref, classOf[BT]) should ===(true)
    }

    "manage sub-channels using classes and traits (unsubscribe classes used with trait)" taggedAs GHExcludeTest in {
      val es = new EventStream(system, false)
      val tm1 = new CC
      val tm2 = new CCATBT
      val a1, a2, a3 = TestProbe()

      es.subscribe(a1.ref, classOf[AT]) should ===(true)
      es.subscribe(a2.ref, classOf[BT]) should ===(true)
      es.subscribe(a2.ref, classOf[CC]) should ===(true)
      es.subscribe(a3.ref, classOf[CC]) should ===(true)
      es.unsubscribe(a2.ref, classOf[CC]) should ===(true)
      es.unsubscribe(a3.ref, classOf[CCATBT]) should ===(true)
      // subscription and unsubscription is async
      awaitAssert {
        es.publish(tm1)
        es.publish(tm2)
        (a1.expectMsgType[AT]: AT) should ===(tm2)
        (a2.expectMsgType[BT]: BT) should ===(tm2)
        (a3.expectMsgType[CC]: CC) should ===(tm1)
      }
      es.unsubscribe(a1.ref, classOf[AT]) should ===(true)
      es.unsubscribe(a2.ref, classOf[BT]) should ===(true)
      es.unsubscribe(a3.ref, classOf[CC]) should ===(true)
    }

    "manage sub-channels using classes and traits (subscribe after publish)" taggedAs GHExcludeTest in {
      val es = new EventStream(system, false)
      val tm1 = new CCATBT
      val a1, a2 = TestProbe()

      es.subscribe(a1.ref, classOf[AT]) should ===(true)
      // subscription and unsubscription is async
      awaitAssert {
        es.publish(tm1)
        (a1.expectMsgType[AT]: AT) should ===(tm1)
      }

      es.subscribe(a2.ref, classOf[BTT]) should ===(true)
      // subscription and unsubscription is async
      awaitAssert {
        es.publish(tm1)
        (a1.expectMsgType[AT]: AT) should ===(tm1)
        (a2.expectMsgType[BTT]: BTT) should ===(tm1)
      }
      es.unsubscribe(a1.ref, classOf[AT]) should ===(true)
      es.unsubscribe(a2.ref, classOf[BTT]) should ===(true)
    }

    "unsubscribe an actor on its termination" taggedAs GHExcludeTest in {
      val sys = ActorSystem("EventStreamSpecUnsubscribeOnTerminated", configUnhandledWithDebug)

      try {
        val es = sys.eventStream
        val a1, a2 = TestProbe()
        val tm = new A

        val target = sys.actorOf(Props(new Actor {
          def receive = { case in => a1.ref.forward(in) }
        }), "to-be-killed")

        es.subscribe(a2.ref, classOf[Any])
        waitForDebugSubscription(es, a2)

        es.subscribe(target, classOf[A]) should ===(true)
        es.subscribe(target, classOf[A]) should ===(false)

        target ! PoisonPill
        fishForDebugMessage(a2, s"unsubscribing $target from all channels")
        fishForDebugMessage(a2, s"unwatching $target")

        es.publish(tm)

        a1.expectNoMessage(1.second)
        a2.expectMsg(tm)
      } finally {
        shutdown(sys)
      }
    }

    // Excluded in GH Actions: https://github.com/akka/akka/issues/30460
    "unsubscribe the actor, when it subscribes already in terminated state" taggedAs GHExcludeTest in {
      val sys = ActorSystem("EventStreamSpecUnsubscribeTerminated", configUnhandledWithDebug)

      try {
        val es = sys.eventStream
        val a1, a2 = TestProbe()

        val target = system.actorOf(Props(new Actor {
          def receive = { case in => a1.ref.forward(in) }
        }), "to-be-killed")

        watch(target)
        target ! PoisonPill
        expectTerminated(target)

        es.subscribe(a2.ref, classOf[Any])
        waitForDebugSubscription(es, a2)

        // target1 is Terminated; When subscribing, it will be unsubscribed by the Unsubscriber right away
        es.subscribe(target, classOf[A]) should ===(true)
        fishForDebugMessage(a2, s"unsubscribing $target from all channels")

        awaitAssert {
          es.subscribe(target, classOf[A]) should ===(true)
        }
        fishForDebugMessage(a2, s"unsubscribing $target from all channels")
      } finally {
        shutdown(sys)
      }
    }

    "not allow initializing a TerminatedUnsubscriber twice" taggedAs GHExcludeTest in {
      val sys = ActorSystem(
        "MustNotAllowDoubleInitOfTerminatedUnsubscriber",
        // debug loglevel to diagose #18630
        ConfigFactory.parseString("akka.loglevel = debug").withFallback(ConfigFactory.load()))
      // initializes an TerminatedUnsubscriber during start

      try {
        val es = sys.eventStream
        val p = TestProbe()

        val refWillBeUsedAsUnsubscriber = es.initUnsubscriber(p.ref)

        refWillBeUsedAsUnsubscriber should equal(false)

      } finally {
        shutdown(sys)
      }
    }

    "unwatch an actor from unsubscriber when that actor unsubscribes from the stream" taggedAs GHExcludeTest in {
      val sys = ActorSystem("MustUnregisterDuringUnsubscribe", configUnhandledWithDebug)

      try {
        val es = sys.eventStream
        val a1, a2 = TestProbe()

        es.subscribe(a1.ref, classOf[Logging.Debug])
        waitForDebugSubscription(es, a1)

        es.subscribe(a2.ref, classOf[A])
        fishForDebugMessage(a1, s"watching ${a2.ref}")

        es.unsubscribe(a2.ref)
        fishForDebugMessage(a1, s"unwatching ${a2.ref}")

      } finally {
        shutdown(sys)
      }
    }

    "unwatch an actor from unsubscriber when that actor unsubscribes from channels it subscribed" taggedAs GHExcludeTest in {
      val sys = ActorSystem("MustUnregisterWhenNoMoreChannelSubscriptions", configUnhandledWithDebug)

      try {
        val es = sys.eventStream
        val a1, a2 = TestProbe()

        es.subscribe(a1.ref, classOf[Logging.Debug])
        waitForDebugSubscription(es, a1)

        es.subscribe(a2.ref, classOf[A])
        es.subscribe(a2.ref, classOf[T])
        fishForDebugMessage(a1, s"watching ${a2.ref}")
        fishForDebugMessage(a1, s"watching ${a2.ref}") // the unsubscriber "starts to watch" each time, as watching is idempotent

        es.unsubscribe(a2.ref, classOf[A]) should equal(true)
        fishForDebugMessage(a1, s"unsubscribing ${a2.ref} from channel class akka.event.EventStreamSpec$$A")
        a1.expectNoMessage(1.second)

        es.unsubscribe(a2.ref, classOf[T]) should equal(true)
        fishForDebugMessage(a1, s"unsubscribing ${a2.ref} from channel interface akka.event.EventStreamSpec$$T")
        fishForDebugMessage(a1, s"unwatching ${a2.ref}, since has no subscriptions")
        a1.expectNoMessage(1.second)

        es.unsubscribe(a2.ref, classOf[T]) should equal(false)

      } finally {
        shutdown(sys)
      }
    }

  }

  private def verifyLevel(bus: LoggingBus, level: Logging.LogLevel): Unit = {
    import Logging._
    val allmsg =
      Seq(Debug("", null, "debug"), Info("", null, "info"), Warning("", null, "warning"), Error("", null, "error"))
    val msg = allmsg.filter(_.level <= level)
    allmsg.foreach(bus.publish)
    msg.foreach(expectMsg(_))
  }

  private def fishForDebugMessage(a: TestProbe, messagePrefix: String): Unit = {
    a.fishForMessage(hint = "expected debug message prefix: " + messagePrefix) {
      case Logging.Debug(_, _, msg: String) if msg.startsWith(messagePrefix) => true
      case _                                                                 => false
    }
  }

  private def waitForDebugSubscription(bus: LoggingBus, a: TestProbe): Unit = {
    // subscription is async, we need to verify it completed before asserting other things
    var n = 0
    awaitAssert {
      n += 1
      val debug = Logging.Debug(n.toString, classOf[EventStreamSpec])
      bus.publish(debug)
      a.expectMsg(debug)
    }
  }

}
