/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import language.postfixOps

import akka.testkit.{ AkkaSpec, EventFilter }
import ActorDSL._
import akka.event.Logging.Warning
import scala.concurrent.{ Await, Future }
import scala.concurrent.util.duration._
import java.util.concurrent.TimeoutException

class ActorDSLSpec extends AkkaSpec {

  val echo = system.actorOf(Props(new Actor {
    def receive = {
      case x ⇒ sender ! x
    }
  }))

  "An Inbox" must {

    "function as implicit sender" in {
      implicit val i = inbox()
      echo ! "hello"
      i.receive() must be("hello")
    }

    "support queueing multiple queries" in {
      val i = inbox()
      import system.dispatcher
      val res = Future.sequence(Seq(
        Future { i.receive() } recover { case x ⇒ x },
        Future { Thread.sleep(100); i.select() { case "world" ⇒ 1 } } recover { case x ⇒ x },
        Future { Thread.sleep(200); i.select() { case "hello" ⇒ 2 } } recover { case x ⇒ x }))
      Thread.sleep(1000)
      res.isCompleted must be(false)
      i.receiver ! 42
      i.receiver ! "hello"
      i.receiver ! "world"
      Await.result(res, 5 second) must be(Seq(42, 1, 2))
    }

    "support selective receives" in {
      val i = inbox()
      i.receiver ! "hello"
      i.receiver ! "world"
      val result = i.select() {
        case "world" ⇒ true
      }
      result must be(true)
      i.receive() must be("hello")
    }

    "have a maximum queue size" in {
      val i = inbox()
      system.eventStream.subscribe(testActor, classOf[Warning])
      for (_ ← 1 to 1000) i.receiver ! 0
      expectNoMsg(1 second)
      EventFilter.warning(start = "dropping message", occurrences = 1) intercept {
        i.receiver ! 42
      }
      expectMsgType[Warning]
      i.receiver ! 42
      expectNoMsg(1 second)
      val gotit = for (_ ← 1 to 1000) yield i.receive()
      gotit must be((1 to 1000) map (_ ⇒ 0))
      intercept[TimeoutException] {
        i.receive(1 second)
      }
    }

    "have a default and custom timeouts" in {
      val i = inbox()
      within(5 seconds, 6 seconds) {
        intercept[TimeoutException](i.receive())
      }
      within(1 second) {
        intercept[TimeoutException](i.receive(100 millis))
      }
    }

  }

  "A lightweight creator" must {

    "support creating regular actors" in {
      val a = actor()(new Act {
        become {
          case "hello" ⇒ sender ! "hi"
        }
      })
      
      implicit val i = inbox()
      a ! "hello"
      i.receive() must be("hi")
    }

    "support setup/teardown" in {
      val a = actor()(new Act {
        setup { testActor ! "started" }
        teardown { testActor ! "stopped" }
      })
      
      system stop a
      expectMsg("started")
      expectMsg("stopped")
    }

    "support restart" in {
      val a = actor()(new Act {
        become {
          case "die" ⇒ throw new Exception
        }
        whenFailing { (cause, msg) ⇒ testActor ! (cause, msg) }
        whenRestarted { cause ⇒ testActor ! cause }
      })
      
      EventFilter[Exception](occurrences = 1) intercept {
        a ! "die"
      }
      expectMsgPF() { case (x: Exception, Some("die")) ⇒ }
      expectMsgPF() { case _: Exception ⇒ }
    }
    
    "supported nested declaration" in {
      val system = this.system
      val a = actor(system, "fred")(new Act {
        val b = actor("barney")(new Act {
          setup { context.parent ! s"hello from ${self}" }
        })
        become {
          case x => testActor ! x
        }
      })
      expectMsg("hello from Actor[akka://ActorDSLSpec/user/fred/barney]")
      lastSender must be(a)
    }

  }
}
