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
import akka.event.EventHandler

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
    case _: EventHandler.Debug ⇒ true
    case _: EventHandler.Info  ⇒ true
    case _                     ⇒ false
  })
  appLogging.eventHandler.notify(filter)
  appAuto.eventHandler.notify(filter)
  appLifecycle.eventHandler.notify(filter)

  def ignoreMute(t: TestKit) {
    t.ignoreMsg {
      case (_: TestEvent.Mute | _: TestEvent.UnMute) ⇒ true
    }
  }

  "A LoggingReceive" must {

    "decorate a Receive" in {
      new TestKit(appLogging) {
        app.eventHandler.addListener(testActor)
        val r: Actor.Receive = {
          case null ⇒
        }
        val log = Actor.LoggingReceive(this, r)
        log.isDefinedAt("hallo")
        expectMsg(1 second, EventHandler.Debug(this, "received unhandled message hallo"))
      }
    }

    "be added on Actor if requested" in {
      new TestKit(appLogging) with ImplicitSender {
        ignoreMute(this)
        app.eventHandler.addListener(testActor)
        val actor = TestActorRef(new Actor {
          def receive = loggable(this) {
            case _ ⇒ reply("x")
          }
        })
        actor ! "buh"
        within(1 second) {
          expectMsg(EventHandler.Debug(actor.underlyingActor, "received handled message buh"))
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
              case EventHandler.Error(_: UnhandledMessageException, `actor`, _) ⇒ true
            }
          }
        }
        actor.stop()
      }
    }

    "not duplicate logging" in {
      new TestKit(appLogging) with ImplicitSender {
        app.eventHandler.addListener(testActor)
        val actor = TestActorRef(new Actor {
          def receive = loggable(this)(loggable(this) {
            case _ ⇒ reply("x")
          })
        })
        actor ! "buh"
        within(1 second) {
          expectMsg(EventHandler.Debug(actor.underlyingActor, "received handled message buh"))
          expectMsg("x")
        }
      }
    }

  }

  "An Actor" must {

    "log AutoReceiveMessages if requested" in {
      new TestKit(appAuto) {
        app.eventHandler.addListener(testActor)
        val actor = TestActorRef(new Actor {
          def receive = {
            case _ ⇒
          }
        })
        actor ! PoisonPill
        expectMsg(300 millis, EventHandler.Debug(actor.underlyingActor, "received AutoReceiveMessage PoisonPill"))
        awaitCond(actor.isShutdown, 100 millis)
      }
    }

    "log LifeCycle changes if requested" in {
      new TestKit(appLifecycle) {
        ignoreMute(this)
        app.eventHandler.addListener(testActor)
        within(2 seconds) {
          val supervisor = TestActorRef[TestLogActor](Props[TestLogActor].withFaultHandler(OneForOneStrategy(List(classOf[Throwable]), 5, 5000)))

          expectMsg(EventHandler.Debug(supervisor, "started"))

          val actor = TestActorRef[TestLogActor](Props[TestLogActor].withSupervisor(supervisor))

          expectMsgPF() {
            case EventHandler.Debug(ref, msg: String) ⇒ ref == supervisor && msg.startsWith("now supervising")
          }

          expectMsg(EventHandler.Debug(actor, "started"))

          supervisor startsMonitoring actor
          expectMsgPF(hint = "now monitoring") {
            case EventHandler.Debug(ref, msg: String) ⇒
              ref == supervisor.underlyingActor && msg.startsWith("now monitoring")
          }

          supervisor stopsMonitoring actor
          expectMsgPF(hint = "stopped monitoring") {
            case EventHandler.Debug(ref, msg: String) ⇒
              ref == supervisor.underlyingActor && msg.startsWith("stopped monitoring")
          }

          filterException[ActorKilledException] {
            actor ! Kill
            expectMsgPF() {
              case EventHandler.Error(_: ActorKilledException, `actor`, "Kill") ⇒ true
            }
            expectMsg(EventHandler.Debug(actor, "restarting"))
          }
          awaitCond(msgAvailable)
          expectMsgPF(hint = "restarted") {
            case EventHandler.Debug(`actor`, "restarted") ⇒ true
          }

          actor.stop()
          expectMsg(EventHandler.Debug(actor, "stopping"))
          supervisor.stop()
        }
      }
    }

  }

}
