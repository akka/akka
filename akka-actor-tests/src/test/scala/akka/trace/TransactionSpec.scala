/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.trace

import akka.actor._
import akka.testkit._
import akka.util.ByteString
import com.typesafe.config.{ Config, ConfigFactory }
import scala.concurrent.duration._

object TransactionSpec {
  val testConfig: Config = ConfigFactory.parseString("""
    akka.tracers = ["akka.trace.TransactionTracer"]
  """)

  case class Message(next: Seq[ActorRef], ids: Seq[String] = Seq.empty)

  class TracedActor extends Actor {
    def receive = {
      case Message(next, ids) ⇒ next.head ! Message(next.tail, ids :+ Transaction.value)
    }
  }
}

class TransactionSpec extends AkkaSpec(TransactionSpec.testConfig) with DefaultTimeout {
  import TransactionSpec._

  "Transaction tracer" must {
    "continue trace context throughout message flow" in {
      val actor1 = system.actorOf(Props[TracedActor], "actor1")
      val actor2 = system.actorOf(Props[TracedActor], "actor2")
      val actor3 = system.actorOf(Props[TracedActor], "actor3")

      Transaction.withValue("42") {
        actor1 ! Message(Seq(actor2, actor3, testActor))
      }

      expectMsg(Message(Seq.empty, Seq("42", "42", "42")))
    }
  }
}
