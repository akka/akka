/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach }
import akka.testkit.{ TestKit, TestActorRef, EventFilter, TestEvent, ImplicitSender }
import akka.util.duration._
import akka.testkit.AkkaSpec
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

  //  override def beforeAll {
  //    app.eventHandler.notify(TestEvent.Mute(EventFilter[UnhandledMessageException],
  //      EventFilter[ActorKilledException], EventFilter.custom {
  //        case d: app.eventHandler.Debug ⇒ true
  //        case _                     ⇒ false
  //      }))
  //  }

  //  ignoreMsg {
  //    case EventHandler.Debug(_, s: String) ⇒
  //      !s.startsWith("received") && s != "started" && s != "stopping" && s != "restarting" &&
  //        s != "restarted" && !s.startsWith("now supervising") && !s.startsWith("stopped supervising") &&
  //        !s.startsWith("now monitoring") && !s.startsWith("stopped monitoring")
  //    case EventHandler.Debug(_, _)                               ⇒ true
  //    case EventHandler.Error(_: UnhandledMessageException, _, _) ⇒ false
  //    case _: app.eventHandler.Error                                  ⇒ true
  //  }

  "A LoggingReceive" ignore {

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
        actor ! "bah"
        within(300 millis) {
          expectMsgPF() {
            case EventHandler.Error(ex: UnhandledMessageException, ref, exMsg) if ref eq actor ⇒ true
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

  "An Actor" ignore {

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
        app.eventHandler.addListener(testActor)
        within(2 seconds) {
          val supervisor = TestActorRef[TestLogActor](Props[TestLogActor].withFaultHandler(OneForOneStrategy(List(classOf[Throwable]), 5, 5000)))

          val actor = TestActorRef[TestLogActor](Props[TestLogActor].withSupervisor(supervisor))
          val actor1 = actor.underlyingActor

          expectMsgPF() {
            case EventHandler.Debug(ref, msg: String) ⇒
              ref == supervisor.underlyingActor && msg.startsWith("now supervising")
          }

          expectMsg(EventHandler.Debug(actor1, "started"))

          supervisor link actor
          expectMsgPF(hint = "now monitoring") {
            case EventHandler.Debug(ref, msg: String) ⇒
              ref == supervisor.underlyingActor && msg.startsWith("now monitoring")
          }

          supervisor unlink actor
          expectMsgPF(hint = "stopped monitoring") {
            case EventHandler.Debug(ref, msg: String) ⇒
              ref == supervisor.underlyingActor && msg.startsWith("stopped monitoring")
          }

          actor ! Kill
          expectMsg(EventHandler.Debug(actor1, "restarting"))
          awaitCond(msgAvailable)
          val actor2 = actor.underlyingActor
          expectMsgPF(hint = "restarted") {
            case EventHandler.Debug(ref, "restarted") if ref eq actor2 ⇒ true
          }

          actor.stop()
          expectMsg(EventHandler.Debug(actor2, "stopping"))
          supervisor.stop()
        }
      }
    }

  }

}
