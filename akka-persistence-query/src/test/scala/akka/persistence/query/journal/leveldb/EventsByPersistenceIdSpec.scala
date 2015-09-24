/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.persistence.query.journal.leveldb

import scala.concurrent.duration._

import akka.actor.ActorRef
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.persistence.query.scaladsl.EventsByTagQuery
import akka.stream.ActorMaterializer
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.AkkaSpec
import akka.testkit.ImplicitSender

object EventsByPersistenceIdSpec {
  val config = """
    akka.loglevel = INFO
    akka.persistence.journal.plugin = "akka.persistence.journal.leveldb"
    akka.persistence.journal.leveldb.dir = "target/journal-EventsByPersistenceIdSpec"
    akka.test.single-expect-default = 10s
    akka.persistence.query.journal.leveldb.refresh-interval = 1s
    """
}

class EventsByPersistenceIdSpec extends AkkaSpec(EventsByPersistenceIdSpec.config)
  with Cleanup with ImplicitSender {
  import EventsByPersistenceIdSpec._

  implicit val mat = ActorMaterializer()(system)

  val queries = PersistenceQuery(system).readJournalFor[LeveldbReadJournal](LeveldbReadJournal.Identifier)

  def setup(persistenceId: String): ActorRef = {
    val ref = system.actorOf(TestActor.props(persistenceId))
    ref ! s"$persistenceId-1"
    ref ! s"$persistenceId-2"
    ref ! s"$persistenceId-3"
    expectMsg(s"$persistenceId-1-done")
    expectMsg(s"$persistenceId-2-done")
    expectMsg(s"$persistenceId-3-done")
    ref
  }

  "Leveldb query EventsByPersistenceId" must {

    "implement standard EventsByTagQuery" in {
      queries.isInstanceOf[EventsByTagQuery] should ===(true)
    }

    "find existing events" in {
      val ref = setup("a")

      val src = queries.currentEventsByPersistenceId("a", 0L, Long.MaxValue)
      src.map(_.event).runWith(TestSink.probe[Any])
        .request(2)
        .expectNext("a-1", "a-2")
        .expectNoMsg(500.millis)
        .request(2)
        .expectNext("a-3")
        .expectComplete()
    }

    "find existing events up to a sequence number" in {
      val ref = setup("b")
      val src = queries.currentEventsByPersistenceId("b", 0L, 2L)
      src.map(_.event).runWith(TestSink.probe[Any])
        .request(5)
        .expectNext("b-1", "b-2")
        .expectComplete()
    }

    "not see new events after demand request" in {
      val ref = setup("f")
      val src = queries.currentEventsByPersistenceId("f", 0L, Long.MaxValue)
      val probe = src.map(_.event).runWith(TestSink.probe[Any])
        .request(2)
        .expectNext("f-1", "f-2")
        .expectNoMsg(100.millis)

      ref ! "f-4"
      expectMsg("f-4-done")

      probe
        .expectNoMsg(100.millis)
        .request(5)
        .expectNext("f-3")
        .expectComplete() // f-4 not seen
    }
  }

  "Leveldb live query EventsByPersistenceId" must {
    "find new events" in {
      val ref = setup("c")
      val src = queries.eventsByPersistenceId("c", 0L, Long.MaxValue)
      val probe = src.map(_.event).runWith(TestSink.probe[Any])
        .request(5)
        .expectNext("c-1", "c-2", "c-3")

      ref ! "c-4"
      expectMsg("c-4-done")

      probe.expectNext("c-4")
    }

    "find new events up to a sequence number" in {
      val ref = setup("d")
      val src = queries.eventsByPersistenceId("d", 0L, 4L)
      val probe = src.map(_.event).runWith(TestSink.probe[Any])
        .request(5)
        .expectNext("d-1", "d-2", "d-3")

      ref ! "d-4"
      expectMsg("d-4-done")

      probe.expectNext("d-4").expectComplete()
    }

    "find new events after demand request" in {
      val ref = setup("e")
      val src = queries.eventsByPersistenceId("e", 0L, Long.MaxValue)
      val probe = src.map(_.event).runWith(TestSink.probe[Any])
        .request(2)
        .expectNext("e-1", "e-2")
        .expectNoMsg(100.millis)

      ref ! "e-4"
      expectMsg("e-4-done")

      probe
        .expectNoMsg(100.millis)
        .request(5)
        .expectNext("e-3")
        .expectNext("e-4")
    }
  }

}
