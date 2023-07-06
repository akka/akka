/*
 * Copyright (C) 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed

import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.pattern.StatusReply
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

object EventWriterSpec {
  def config =
    ConfigFactory.parseString(s"""
      akka.persistence.journal.inmem.delay-writes=20ms

      akka.persistence.journal.inmem2 = $${akka.persistence.journal.inmem}
      akka.persistence.journal.inmem2.test-serialization = true
    """).withFallback(ConfigFactory.load()).resolve()
}

class EventWriterSpec extends ScalaTestWithActorTestKit(EventWriterSpec.config) with AnyWordSpecLike with LogCapturing {

  implicit val ec: ExecutionContext = testKit.system.executionContext

  "The event writer" should {

    "handle duplicates" in {
      val writer = spawn(EventWriter("akka.persistence.journal.inmem"))

      val probe = createTestProbe[StatusReply[EventWriter.WriteAck]]()
      writer ! EventWriter.Write("pid1", 1L, "one", None, Set.empty, probe.ref)
      probe.receiveMessage().getValue

      // should also be ack:ed
      writer ! EventWriter.Write("pid1", 1L, "one", None, Set.empty, probe.ref)
      probe.receiveMessage().getValue
    }

    "handle batched duplicates" in {
      val writer = spawn(EventWriter("akka.persistence.journal.inmem"))
      val probe = createTestProbe[StatusReply[EventWriter.WriteAck]]()
      for (n <- 0 to 10) {
        writer ! EventWriter.Write("pid1", n.toLong, n.toString, None, Set.empty, probe.ref)
      }
      for (n <- 0 to 10) {
        writer ! EventWriter.Write("pid1", n.toLong, n.toString, None, Set.empty, probe.ref)
      }
      probe.receiveMessages(20) // all should be ack:ed
    }

    "handle batches with half duplicates" in {
      val writer = spawn(EventWriter("akka.persistence.journal.inmem"))
      val probe = createTestProbe[StatusReply[EventWriter.WriteAck]]()
      for (n <- 0 to 10) {
        writer ! EventWriter.Write("pid1", n.toLong, n.toString, None, Set.empty, probe.ref)
      }
      for (n <- 5 to 15) {
        writer ! EventWriter.Write("pid1", n.toLong, n.toString, None, Set.empty, probe.ref)
      }
      probe.receiveMessages(20) // all should be ack:ed
    }

    "handle writes to many pids" in {
      val writer = spawn(EventWriter("akka.persistence.journal.inmem"))
      val probe = createTestProbe[StatusReply[EventWriter.WriteAck]]()
      (0 to 1000).map { pidN =>
        Future {
          for (n <- 0 to 20) {
            writer ! EventWriter.Write(s"pid$pidN", n.toLong, n.toString, None, Set.empty, probe.ref)
          }
        }
      }
      probe.receiveMessages(20 * 1000, 20.seconds)
    }

    class WithoutSerializerDefined
    "pass actual failures back" in {
      val writer = spawn(EventWriter("akka.persistence.journal.inmem2"))
      val probe = createTestProbe[StatusReply[EventWriter.WriteAck]]()
      writer ! EventWriter.Write("pid1", 1L, new WithoutSerializerDefined, None, Set.empty, probe.ref)
      probe.receiveMessage().getError
    }
  }

}
