/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.actor

import org.scalatest.{ WordSpec, BeforeAndAfterAll, BeforeAndAfterEach }
import org.scalatest.matchers.MustMatchers
import akka.testkit.{ TestKit, TestActorRef }
import akka.event.EventHandler
import Actor._
import akka.util.duration._
import akka.config.Config.config
import akka.config.Supervision._

class LoggingReceiveSpec
  extends WordSpec
  with BeforeAndAfterEach
  with BeforeAndAfterAll
  with MustMatchers
  with TestKit {

  val level = EventHandler.level

  override def beforeAll {
    EventHandler.addListener(testActor)
    EventHandler.level = EventHandler.DebugLevel
  }

  override def afterAll {
    EventHandler.removeListener(testActor)
    EventHandler.level = level
  }

  override def afterEach {
    val f1 = Actor.getClass.getDeclaredField("addLoggingReceive")
    f1.setAccessible(true)
    f1.setBoolean(Actor, false)
    val f2 = Actor.getClass.getDeclaredField("debugAutoReceive")
    f2.setAccessible(true)
    f2.setBoolean(Actor, false)
    val f3 = Actor.getClass.getDeclaredField("debugLifecycle")
    f3.setAccessible(true)
    f3.setBoolean(Actor, false)
  }

  ignoreMsg {
    case EventHandler.Debug(_, s: String) ⇒
      !s.startsWith("received") && s != "started" && s != "stopping" && s != "restarting" &&
        s != "restarted" && !s.startsWith("now supervising") && !s.startsWith("stopped supervising")
    case EventHandler.Debug(_, _)                               ⇒ true
    case EventHandler.Error(_: UnhandledMessageException, _, _) ⇒ false
    case _: EventHandler.Error                                  ⇒ true
  }

  "A LoggingReceive" must {

    "decorate a Receive" in {
      val r: Receive = {
        case null ⇒
      }
      val log = LoggingReceive(this, r)
      log.isDefinedAt("hallo")
      expectMsg(1 second, EventHandler.Debug(this, "received unhandled message hallo"))
    }

    "be added on Actor if requested" in {
      val f = Actor.getClass.getDeclaredField("addLoggingReceive")
      f.setAccessible(true)
      f.setBoolean(Actor, true)
      val actor = TestActorRef(new Actor {
        def receive = loggable(this) {
          case _ ⇒ self reply "x"
        }
      }).start()
      actor ! "buh"
      within(1 second) {
        expectMsg(EventHandler.Debug(actor.underlyingActor, "received handled message buh"))
        expectMsg("x")
      }
      val r: Receive = {
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

    "not duplicate logging" in {
      val f = Actor.getClass.getDeclaredField("addLoggingReceive")
      f.setAccessible(true)
      f.setBoolean(Actor, true)
      val actor = TestActorRef(new Actor {
        def receive = loggable(this)(loggable(this) {
          case _ ⇒ self reply "x"
        })
      }).start()
      actor ! "buh"
      within(1 second) {
        expectMsg(EventHandler.Debug(actor.underlyingActor, "received handled message buh"))
        expectMsg("x")
      }
    }

  }

  "An Actor" must {

    "log AutoReceiveMessages if requested" in {
      val f = Actor.getClass.getDeclaredField("debugAutoReceive")
      f.setAccessible(true)
      f.setBoolean(Actor, true)
      val actor = TestActorRef(new Actor {
        def receive = {
          case _ ⇒
        }
      }).start()
      actor ! PoisonPill
      expectMsg(300 millis, EventHandler.Debug(actor.underlyingActor, "received AutoReceiveMessage PoisonPill"))
    }

    "log LifeCycle changes if requested" in {
      within(2 seconds) {
        val supervisor = TestActorRef(new Actor {
          self.faultHandler = OneForOneStrategy(List(classOf[Throwable]), 5, 5000)
          def receive = {
            case _ ⇒
          }
        }).start()
        val f = Actor.getClass.getDeclaredField("debugLifecycle")
        f.setAccessible(true)
        f.setBoolean(Actor, true)
        val actor = TestActorRef(new Actor {
          def receive = {
            case _ ⇒
          }
        }).start()
        val actor1 = actor.underlyingActor
        expectMsg(EventHandler.Debug(actor1, "started"))
        supervisor link actor
        expectMsgPF() {
          case EventHandler.Debug(ref, msg: String) ⇒
            ref == supervisor.underlyingActor && msg.startsWith("now supervising")
        }
        actor ! Kill
        expectMsg(EventHandler.Debug(actor1, "restarting"))
        expectMsgPF() {
          case EventHandler.Debug(ref, "restarted") if ref == actor.underlyingActor ⇒ true
        }
        supervisor unlink actor
        expectMsgPF() {
          case EventHandler.Debug(ref, msg: String) ⇒
            ref == supervisor.underlyingActor && msg.startsWith("stopped supervising")
        }
        actor.stop()
        expectMsg(EventHandler.Debug(actor.underlyingActor, "stopping"))
        supervisor.stop()
      }
    }

  }

}
