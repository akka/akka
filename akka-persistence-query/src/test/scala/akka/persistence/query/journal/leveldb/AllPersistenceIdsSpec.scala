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
import akka.persistence.query.AllPersistenceIds

object AllPersistenceIdsSpec {
  val config = """
    akka.loglevel = INFO
    akka.persistence.journal.plugin = "akka.persistence.journal.leveldb"
    akka.persistence.journal.leveldb.dir = "target/journal-AllPersistenceIdsSpec"
    """
}

class AllPersistenceIdsSpec extends AkkaSpec(AllPersistenceIdsSpec.config)
  with Cleanup with ImplicitSender {
  import AllPersistenceIdsSpec._

  implicit val mat = ActorMaterializer()(system)

  val queries = PersistenceQuery(system).readJournalFor(LeveldbReadJournal.Identifier)

  "Leveldb query AllPersistenceIds" must {
    "find existing persistenceIds" in {
      system.actorOf(TestActor.props("a")) ! "a1"
      expectMsg("a1-done")
      system.actorOf(TestActor.props("b")) ! "b1"
      expectMsg("b1-done")
      system.actorOf(TestActor.props("c")) ! "c1"
      expectMsg("c1-done")

      val src = queries.query(AllPersistenceIds, NoRefresh)
      src.runWith(TestSink.probe[String])
        .request(5)
        .expectNextUnordered("a", "b", "c")
        .expectComplete()
    }

    "find new persistenceIds" in {
      // a, b, c created by previous step
      system.actorOf(TestActor.props("d")) ! "d1"
      expectMsg("d1-done")

      val src = queries.query(AllPersistenceIds)
      val probe = src.runWith(TestSink.probe[String])
        .request(5)
        .expectNextUnorderedN(List("a", "b", "c", "d"))

      system.actorOf(TestActor.props("e")) ! "e1"
      probe.expectNext("e")

      val more = (1 to 100).map("f" + _)
      more.foreach { p ⇒
        system.actorOf(TestActor.props(p)) ! p
      }

      probe.request(100)
      probe.expectNextUnorderedN(more)

    }
  }

}
