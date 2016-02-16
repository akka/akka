/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.instrument

import akka.actor._
import akka.testkit._
import akka.testkit.TestActors.EchoActor
import com.typesafe.config.{ Config, ConfigFactory }

object InstrumentationSpec {
  val testConfig: Config = ConfigFactory.parseString("""
    akka.instrumentations = ["akka.instrument.CountInstrumentation"]
  """)

  val printConfig: Config = ConfigFactory.parseString("""
    akka.instrumentations = ["akka.instrument.PrintInstrumentation", "akka.instrument.CountInstrumentation"]
    akka.print-instrumentation.muted = true
  """)
}

abstract class AbstractInstrumentationSpec(_config: Config) extends AkkaSpec(_config) with ImplicitSender with DefaultTimeout {
  val instrumentation = CountInstrumentation(system)

  "Actor tracing" must {

    "instrument actor system start" in {
      instrumentation.counts.systemStarted.get should be(1)
    }

    "instrument actor lifecycle" in {
      instrumentation.counts.reset()
      val actor = system.actorOf(Props(new Actor {
        def receive = {
          case message ⇒
            sender ! message
            context.stop(self)
        }
      }))
      actor ! "message"
      expectMsg("message")
      instrumentation.counts.actorCreated.get should be(1)
      instrumentation.counts.actorStarted.get should be(1)
      watch(actor)
      expectTerminated(actor)
      instrumentation.counts.actorShutdown.get should be(1)
    }

    "instrument actor messages" in {
      instrumentation.counts.reset()
      val actor = system.actorOf(Props(new EchoActor))
      actor ! "message"
      expectMsg("message")
      instrumentation.counts.actorTold.get should be(2)
      instrumentation.counts.actorReceived.get should be(2)
      actor ! "message"
      expectMsg("message")
      instrumentation.counts.actorTold.get should be(4)
      instrumentation.counts.actorReceived.get should be(4)
      // actor completed for last message may not have been recorded yet
      instrumentation.counts.actorCompleted.get should be >= 2L
    }

    "instrument events" in {
      instrumentation.counts.reset()
      val actor = system.actorOf(Props(new Actor with ActorLogging {
        def receive = {
          case "echo" ⇒
            sender ! "echo"
          case "dead letter" ⇒
            system.deadLetters ! "A dead letter"; sender ! "dead letter"
          case "warning" ⇒
            log.warning("A warning"); sender ! "warning"
          case "error" ⇒
            log.error("An error"); sender ! "error"
          case "failure" ⇒ throw new IllegalStateException("failure")
        }
      }))
      actor ! "dead letter"
      expectMsg("dead letter")
      instrumentation.counts.eventDeadLetter.get should be(1)
      actor ! "warning"
      expectMsg("warning")
      instrumentation.counts.eventLogWarning.get should be(1)
      actor ! "error"
      expectMsg("error")
      instrumentation.counts.eventLogError.get should be(1)
      actor ! "unhandled"
      actor ! "echo"
      expectMsg("echo")
      instrumentation.counts.eventUnhandled.get should be(1)
      actor ! "failure"
      actor ! "echo"
      expectMsg("echo")
      instrumentation.counts.eventActorFailure.get should be(1)
    }

    "instrument actor system shutdown" in {
      system.shutdown()
      system.awaitTermination(timeout.duration)
      instrumentation.counts.systemShutdown.get should be(1)
    }
  }
}

class InstrumentationSpec extends AbstractInstrumentationSpec(InstrumentationSpec.testConfig)

class InstrumentationPrintSpec extends AbstractInstrumentationSpec(InstrumentationSpec.printConfig)
