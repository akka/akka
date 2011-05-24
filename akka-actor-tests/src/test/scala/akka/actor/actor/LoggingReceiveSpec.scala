/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.actor

import org.scalatest.{ WordSpec, BeforeAndAfterAll }
import org.scalatest.matchers.MustMatchers
import akka.testkit.{ TestKit, TestActorRef }
import akka.event.EventHandler
import Actor._
import akka.util.duration._
import akka.config.Config.config

class LoggingReceiveSpec
    extends WordSpec
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
    val f1 = Actor.getClass.getDeclaredField("addLoggingReceive")
    f1.setAccessible(true)
    f1.setBoolean(Actor, false)
    val f2 = Actor.getClass.getDeclaredField("debugAutoReceive")
    f2.setAccessible(true)
    f2.setBoolean(Actor, false)
  }

  ignoreMsg {
    case EventHandler.Debug(_, s : String) if s.startsWith("received") => false
    case EventHandler.Debug(_, _) => true
  }

  "A LoggingReceive" must {

    "decorate a Receive" in {
      val r : Receive = {
        case null =>
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
          def receive = loggingReceive {
            case _ => self reply "x"
          }
        }).start()
      actor ! "buh"
      within (1 second) {
        expectMsg(EventHandler.Debug(actor.underlyingActor, "received handled message buh"))
        expectMsg("x")
      }
      val r : Receive = {
        case null =>
      }
      actor ! HotSwap(_ => r, false)
      actor ! "bah"
      within (300 millis) {
        expectMsgPF() {
          case EventHandler.Error(ex : UnhandledMessageException, ref, "bah") if ref eq actor => true
        }
      }
      actor.stop()
    }

    "not duplicate logging" in {
      // addLoggingReceive is still on from previous test
      val actor = TestActorRef(new Actor {
          def receive = loggingReceive(loggingReceive {
            case _ => self reply "x"
          })
        }).start()
      actor ! "buh"
      within (1 second) {
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
            case _ =>
          }
        }).start()
      actor ! PoisonPill
      expectMsg(300 millis, EventHandler.Debug(actor.underlyingActor, "received AutoReceiveMessage PoisonPill"))
    }

  }

}
