/*
 * Copyright (C) 2015-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query.journal.inmem

import akka.NotUsed
import akka.actor.ActorRef
import akka.persistence.query.journal.inmem.scaladsl.InmemReadJournal
import akka.persistence.query.journal.leveldb.TestActor
import akka.persistence.query.{ EventEnvelope, PersistenceQuery }
import akka.stream.scaladsl.Source
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.{ AkkaSpec, ImplicitSender, WithLogCapturing }

import scala.concurrent.duration._

object EventsByPersistenceIdSpec {
  val config = """
    akka.loglevel = DEBUG
    akka.loggers = ["akka.testkit.SilenceAllTestEventListener"]
    akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
    # test is using Java serialization and not priority to rewrite
    akka.actor.allow-java-serialization = on
    akka.actor.warn-about-java-serializer-usage = off
    """
}

class EventsByPersistenceIdSpec
    extends AkkaSpec(EventsByPersistenceIdSpec.config)
    with ImplicitSender
    with WithLogCapturing {

  val queries = PersistenceQuery(system).readJournalFor[InmemReadJournal](InmemReadJournal.Identifier)

  def setup(persistenceId: String): ActorRef = {
    val ref = setupEmpty(persistenceId)
    ref ! s"$persistenceId-1"
    ref ! s"$persistenceId-2"
    ref ! s"$persistenceId-3"
    expectMsg(s"$persistenceId-1-done")
    expectMsg(s"$persistenceId-2-done")
    expectMsg(s"$persistenceId-3-done")
    ref
  }

  def setupEmpty(persistenceId: String): ActorRef = {
    system.actorOf(TestActor.props(persistenceId))
  }

  "Inmem live query EventsByPersistenceId" must {
    "find new events" in {
      val ref = setup("c")
      val src = queries.eventsByPersistenceId("c", 0L, Long.MaxValue)
      val probe = src.map(_.event).runWith(TestSink.probe[Any]).request(5).expectNext("c-1", "c-2", "c-3")

      ref ! "c-4"
      expectMsg("c-4-done")

      probe.expectNext("c-4")
    }

    "find new events up to a sequence number" in {
      val ref = setup("d")
      val src = queries.eventsByPersistenceId("d", 0L, 4L)
      val probe = src.map(_.event).runWith(TestSink.probe[Any]).request(5).expectNext("d-1", "d-2", "d-3")

      ref ! "d-4"
      expectMsg("d-4-done")

      probe.expectNext("d-4").expectComplete()
    }

    "find new events after demand request" in {
      val ref = setup("e")
      val src = queries.eventsByPersistenceId("e", 0L, Long.MaxValue)
      val probe =
        src.map(_.event).runWith(TestSink.probe[Any]).request(2).expectNext("e-1", "e-2").expectNoMessage(100.millis)

      ref ! "e-4"
      expectMsg("e-4-done")

      probe.expectNoMessage(100.millis).request(5).expectNext("e-3").expectNext("e-4")
    }

    "include timestamp in EventEnvelope" in {
      setup("n")

      val src = queries.eventsByPersistenceId("n", 0L, Long.MaxValue)
      val probe = src.runWith(TestSink.probe[EventEnvelope])

      probe.request(5)
      probe.expectNext().timestamp should be > 0L
      probe.expectNext().timestamp should be > 0L
      probe.cancel()
    }

    "not complete for empty persistence id" in {
      val src = queries.eventsByPersistenceId("o", 0L, Long.MaxValue)
      val probe =
        src.map(_.event).runWith(TestSink.probe[Any]).request(2)

      probe.expectNoMessage(200.millis) // must not complete

      val ref = setupEmpty("o")
      ref ! "o-1"
      expectMsg(s"o-1-done")

      probe.cancel()
    }

    "return metadata in queries" in {
      val ref = setupEmpty("with-meta")
      ref ! TestActor.WithMeta("a", "m-1")
      ref ! TestActor.WithMeta("b", "m-2")
      val src: Source[EventEnvelope, NotUsed] = queries.eventsByPersistenceId("with-meta", 0L, Long.MaxValue)
      val probe =
        src.runWith(TestSink.probe[Any]).request(3)
      probe.expectNextPF {
        case e @ EventEnvelope(_, "with-meta", 1L, "a") if e.eventMetadata.contains("m-1") =>
      }

      probe.expectNextPF {
        case e @ EventEnvelope(_, "with-meta", 2L, "b") if e.eventMetadata.contains("m-2") =>
      }
    }
  }
}
