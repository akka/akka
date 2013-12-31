/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.trace

import akka.actor._
import akka.testkit._
import akka.util.ByteString
import com.typesafe.config.{ Config, ConfigFactory }
import scala.concurrent.duration._

object TracerSpec {
  val testConfig: Config = ConfigFactory.parseString("""
    # print tracer can be added for debugging: "akka.trace.PrintTracer"
    akka.tracers = ["akka.trace.CountTracer"]
  """)
}

class TracerSpec extends AkkaSpec(TracerSpec.testConfig) with ImplicitSender with DefaultTimeout {
  val tracer = CountTracer(system)

  "Actor tracing" must {

    "trace actor system start" in {
      tracer.counts.systemStarted.get should be(1)
    }

    "trace actor messages" in {
      tracer.counts.reset()
      val actor = system.actorOf(Props(new Actor {
        def receive = { case message ⇒ sender ! message }
      }))
      actor ! "message"
      expectMsg("message")
      tracer.counts.actorTold.get should be(2)
      tracer.counts.actorReceived.get should be(2)
      // actor completed for test actor may not have been recorded yet
      tracer.counts.actorCompleted.get should be >= 1L
    }

    "trace actor system shutdown" in {
      system.shutdown()
      system.awaitTermination(timeout.duration)
      tracer.counts.systemShutdown.get should be(1)
    }
  }
}
