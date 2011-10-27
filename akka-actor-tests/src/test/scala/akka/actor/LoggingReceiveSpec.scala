/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach }
import akka.util.duration._
import akka.testkit._
import org.scalatest.WordSpec
import akka.AkkaApplication
import akka.AkkaApplication.defaultConfig
import akka.config.Configuration
import akka.event.Logging

object LoggingReceiveSpec {
  class TestLogActor extends Actor {
    def receive = { case _ ⇒ }
  }
}

class LoggingReceiveSpec extends WordSpec with BeforeAndAfterEach with BeforeAndAfterAll {

  import LoggingReceiveSpec._

  val config = defaultConfig ++ Configuration("akka.event-handler-level" -> "DEBUG")
  val appLogging = AkkaApplication("logging", config ++ Configuration("akka.actor.debug.receive" -> true))
  val appAuto = AkkaApplication("autoreceive", config ++ Configuration("akka.actor.debug.autoreceive" -> true))
  val appLifecycle = AkkaApplication("lifecycle", config ++ Configuration("akka.actor.debug.lifecycle" -> true))

  val filter = TestEvent.Mute(EventFilter.custom {
    case _: Logging.Debug ⇒ true
    case _: Logging.Info  ⇒ true
    case _                ⇒ false
  })
  appLogging.mainbus.publish(filter)
  appAuto.mainbus.publish(filter)
  appLifecycle.mainbus.publish(filter)

  def ignoreMute(t: TestKit) {
    t.ignoreMsg {
      case (_: TestEvent.Mute | _: TestEvent.UnMute) ⇒ true
    }
  }

  "A LoggingReceive" must {

    "decorate a Receive" in {
      new TestKit(appLogging) {
        app.mainbus.subscribe(testActor, classOf[Logging.Debug])
        val r: Actor.Receive = {
          case null ⇒
        }
        val log = Actor.LoggingReceive(this, r)
        log.isDefinedAt("hallo")
        expectMsg(1 second, Logging.Debug(this, "received unhandled message hallo"))
      }.app.stop()
    }

    "be added on Actor if requested" in {
      new TestKit(appLogging) with ImplicitSender {
        ignoreMute(this)
        app.mainbus.subscribe(testActor, classOf[Logging.Debug])
        val actor = TestActorRef(new Actor {
          def receive = loggable(this) {
            case _ ⇒ channel ! "x"
          }
        })
        actor ! "buh"
        within(1 second) {
          expectMsg(Logging.Debug(actor.underlyingActor, "received handled message buh"))
          expectMsg("x")
        }
        val r: Actor.Receive = {
          case null ⇒
        }
        actor ! HotSwap(_ ⇒ r, false)
        filterException[UnhandledMessageException] {
          within(300 millis) {
            actor ! "bah"
            expectMsgPF() {
              case Logging.Error(_: UnhandledMessageException, `actor`, _) ⇒ true
            }
          }
        }
      }.app.stop()
    }

    "not duplicate logging" in {
      new TestKit(appLogging) with ImplicitSender {
        app.mainbus.subscribe(testActor, classOf[Logging.Debug])
        val actor = TestActorRef(new Actor {
          def receive = loggable(this)(loggable(this) {
            case _ ⇒ channel ! "x"
          })
        })
        actor ! "buh"
        within(1 second) {
          expectMsg(Logging.Debug(actor.underlyingActor, "received handled message buh"))
          expectMsg("x")
        }
      }.app.stop()
    }

  }

  "An Actor" must {

    "log AutoReceiveMessages if requested" in {
      new TestKit(appAuto) {
        app.mainbus.subscribe(testActor, classOf[Logging.Debug])
        val actor = TestActorRef(new Actor {
          def receive = {
            case _ ⇒
          }
        })
        actor ! PoisonPill
        expectMsg(300 millis, Logging.Debug(actor.underlyingActor, "received AutoReceiveMessage PoisonPill"))
        awaitCond(actor.isShutdown, 100 millis)
      }.app.stop()
    }

    // TODO remove ignore as soon as logging is working properly during start-up again
    "log LifeCycle changes if requested" ignore {
      new TestKit(appLifecycle) {
        ignoreMute(this)
        app.mainbus.subscribe(testActor, classOf[Logging.Debug])
        within(2 seconds) {
          val supervisor = TestActorRef[TestLogActor](Props[TestLogActor].withFaultHandler(OneForOneStrategy(List(classOf[Throwable]), 5, 5000)))

          expectMsg(Logging.Debug(supervisor, "started"))

          val actor = new TestActorRef[TestLogActor](app, Props[TestLogActor], supervisor, "none")

          expectMsgPF() {
            case Logging.Debug(ref, msg: String) ⇒ ref == supervisor && msg.startsWith("now supervising")
          }

          expectMsg(Logging.Debug(actor, "started"))

          supervisor startsMonitoring actor
          expectMsgPF(hint = "now monitoring") {
            case Logging.Debug(ref, msg: String) ⇒
              ref == supervisor.underlyingActor && msg.startsWith("now monitoring")
          }

          supervisor stopsMonitoring actor
          expectMsgPF(hint = "stopped monitoring") {
            case Logging.Debug(ref, msg: String) ⇒
              ref == supervisor.underlyingActor && msg.startsWith("stopped monitoring")
          }

          filterException[ActorKilledException] {
            actor ! Kill
            expectMsgPF() {
              case Logging.Error(_: ActorKilledException, `actor`, "Kill") ⇒ true
            }
            expectMsg(Logging.Debug(actor, "restarting"))
          }
          awaitCond(msgAvailable)
          expectMsgPF(hint = "restarted") {
            case Logging.Debug(`actor`, "restarted") ⇒ true
          }

          actor.stop()
          expectMsg(Logging.Debug(actor, "stopping"))
          supervisor.stop()
        }
      }.app.stop()
    }

  }

}
