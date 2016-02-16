/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.instrument

import akka.actor._
import akka.testkit.TestActors.EchoActor
import akka.testkit._
import com.typesafe.config.{ Config, ConfigFactory }
import scala.concurrent.duration._

object TraceInstrumentationSpec {
  val testConfig: Config = ConfigFactory.parseString("""
    akka.instrumentations = ["akka.instrument.TraceInstrumentation"]
  """)

  val printConfig: Config = ConfigFactory.parseString("""
    akka.instrumentations = ["akka.instrument.PrintInstrumentation", "akka.instrument.TraceInstrumentation"]
    akka.print-instrumentation.muted = true
  """)

  case class Message(next: Seq[ActorRef], res: Seq[String] = Seq.empty)

  class SingleMessageActor extends Actor {
    def receive = {
      case Message(next, res) ⇒ next.head ! Message(next.tail, res :+ Trace.value.identifier)
    }
  }

  case class Fanout(aggregator: ActorRef, spread: Set[ActorRef])

  class FanoutMessageActor extends Actor {
    def receive = {
      case Fanout(aggregator, spread) ⇒
        val targets = spread - self
        if (targets.isEmpty) aggregator ! Trace.value.identifier
        else targets.foreach(_ ! Fanout(aggregator, targets))
    }
  }
}

abstract class AbstractTraceInstrumentationSpec(_config: Config) extends AkkaSpec(_config) with DefaultTimeout {
  import TraceInstrumentationSpec._

  "Trace instrumentation" must {
    "continue context throughout single message flow" in {
      val actor1 = system.actorOf(Props[SingleMessageActor], "actor1")
      val actor2 = system.actorOf(Props[SingleMessageActor], "actor2")
      val actor3 = system.actorOf(Props[SingleMessageActor], "actor3")

      Trace.withValue("42") {
        actor1 ! Message(Seq(actor2, actor3, testActor))
      }

      expectMsg(Message(Seq.empty, Seq(
        "42 <- actor1",
        "42 <- actor1 <- actor2",
        "42 <- actor1 <- actor2 <- actor3")))
    }

    "fan out context through multiple message flow" in {
      val actor4 = system.actorOf(Props[FanoutMessageActor], "actor4")
      val actor5 = system.actorOf(Props[FanoutMessageActor], "actor5")
      val actor6 = system.actorOf(Props[FanoutMessageActor], "actor6")
      val actor7 = system.actorOf(Props[FanoutMessageActor], "actor7")

      Trace.withValue("17") {
        actor4 ! Fanout(testActor, Set(actor5, actor6, actor7))
      }

      val res = receiveN(3 * 2).foldLeft(Set.empty[String]) {
        case (res, string: String) ⇒ res + string
      }

      res should be(Set(
        "17 <- actor4 <- actor5 <- actor6 <- actor7",
        "17 <- actor4 <- actor5 <- actor7 <- actor6",
        "17 <- actor4 <- actor6 <- actor5 <- actor7",
        "17 <- actor4 <- actor6 <- actor7 <- actor5",
        "17 <- actor4 <- actor7 <- actor5 <- actor6",
        "17 <- actor4 <- actor7 <- actor6 <- actor5"))

      expectNoMsg(100.millis)
    }

    "be picked up during actor creation" in {
      Trace.withValue(Trace("47", testActor)) {
        val actor8 = system.actorOf(Props(new Actor {
          def receive = {
            case s: String ⇒
              val child = context.actorOf(Props(new EchoActor), "child")
              sender ! child
              child ! ((s, child))
            case m ⇒ testActor ! m
          }
        }), "actor8")

        expectMsg(Trace("47", testActor))
        actor8.tell("create", testActor)
        val child = expectMsgType[ActorRef]
        expectMsg(Trace("47 <- actor8", actor8))
        expectMsg(("create", child))
        expectNoMsg(100.millis)
      }
    }
  }
}

class TraceInstrumentationSpec extends AbstractTraceInstrumentationSpec(TraceInstrumentationSpec.testConfig)

class TraceInstrumentationPrintSpec extends AbstractTraceInstrumentationSpec(TraceInstrumentationSpec.printConfig)
