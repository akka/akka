/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.persistence.query.journal.leveldb

import scala.concurrent.duration._
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.persistence.query.EventsByPersistenceId
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.RefreshInterval
import akka.stream.ActorMaterializer
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.ImplicitSender
import akka.testkit.TestKit
import akka.persistence.query.NoRefresh
import akka.testkit.AkkaSpec

object EventsByPersistenceIdSpec {
  val config = """
    akka.loglevel = INFO
    akka.persistence.journal.plugin = "akka.persistence.journal.leveldb"
    akka.persistence.journal.leveldb.dir = "target/journal-EventsByPersistenceIdSpec"
    """
}

class EventsByPersistenceIdSpec extends AkkaSpec(EventsByPersistenceIdSpec.config)
  with Cleanup with ImplicitSender {
  import EventsByPersistenceIdSpec._

  implicit val mat = ActorMaterializer()(system)

  val refreshInterval = RefreshInterval(1.second)

  val queries = PersistenceQuery(system).readJournalFor(LeveldbReadJournal.Identifier)

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
    "find existing events" in {
      val ref = setup("a")

      val src = queries.query(EventsByPersistenceId("a", 0L, Long.MaxValue), NoRefresh)
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
      val src = queries.query(EventsByPersistenceId("b", 0L, 2L), NoRefresh)
      src.map(_.event).runWith(TestSink.probe[Any])
        .request(5)
        .expectNext("b-1", "b-2")
        .expectComplete()
    }
  }

  "Leveldb live query EventsByPersistenceId" must {
    "find new events" in {
      val ref = setup("c")
      val src = queries.query(EventsByPersistenceId("c", 0L, Long.MaxValue), refreshInterval)
      val probe = src.map(_.event).runWith(TestSink.probe[Any])
        .request(5)
        .expectNext("c-1", "c-2", "c-3")

      ref ! "c-4"
      expectMsg("c-4-done")

      probe.expectNext("c-4")
    }

    "find new events up to a sequence number" in {
      val ref = setup("d")
      val src = queries.query(EventsByPersistenceId("d", 0L, 4L), refreshInterval)
      val probe = src.map(_.event).runWith(TestSink.probe[Any])
        .request(5)
        .expectNext("d-1", "d-2", "d-3")

      ref ! "d-4"
      expectMsg("d-4-done")

      probe.expectNext("d-4").expectComplete()
    }
  }

}
