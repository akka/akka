/*
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.persistence.query

import akka.persistence.journal.{ EventAdapter, EventSeq }
import akka.testkit.AkkaSpec
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.journal.leveldb.LeveldbReadJournal
import akka.persistence.journal.leveldb.Tagged
import akka.persistence.query.EventsByPersistenceId
import akka.stream.scaladsl.Source
import akka.persistence.query.EventEnvelope
import akka.stream.ActorMaterializer
import akka.persistence.query.AllPersistenceIds
import akka.persistence.query.EventsByTag
import scala.annotation.tailrec

object LeveldbPersistenceQueryDocSpec {
  //#tagger
  import akka.persistence.journal.WriteEventAdapter
  import akka.persistence.journal.leveldb.Tagged

  class ColorTagger extends WriteEventAdapter {
    val colors = Set("green", "black", "blue")
    override def toJournal(event: Any): Any = event match {
      case s: String ⇒
        var tags = colors.foldLeft(Set.empty[String]) { (acc, c) ⇒
          if (s.contains(c)) acc + c else acc
        }
        if (tags.isEmpty) event
        else Tagged(event, tags)
      case _ ⇒ event
    }

    override def manifest(event: Any): String = ""
  }
  //#tagger
}

class LeveldbPersistenceQueryDocSpec(config: String) extends AkkaSpec(config) {

  def this() = this("")

  "LeveldbPersistentQuery" must {
    "demonstrate how get ReadJournal" in {
      //#get-read-journal
      import akka.persistence.query.PersistenceQuery
      import akka.persistence.query.journal.leveldb.LeveldbReadJournal

      val queries = PersistenceQuery(system).readJournalFor(LeveldbReadJournal.Identifier)
      //#get-read-journal
    }

    "demonstrate EventsByPersistenceId" in {
      //#EventsByPersistenceId
      import akka.persistence.query.EventsByPersistenceId
      implicit val mat = ActorMaterializer()(system)
      val queries = PersistenceQuery(system).readJournalFor(LeveldbReadJournal.Identifier)

      val src: Source[EventEnvelope, Unit] =
        queries.query(EventsByPersistenceId("some-persistence-id", 0L, Long.MaxValue))

      val events: Source[Any, Unit] = src.map(_.event)
      //#EventsByPersistenceId
    }

    "demonstrate AllPersistenceIds" in {
      //#AllPersistenceIds
      import akka.persistence.query.AllPersistenceIds
      implicit val mat = ActorMaterializer()(system)
      val queries = PersistenceQuery(system).readJournalFor(LeveldbReadJournal.Identifier)

      val src: Source[String, Unit] = queries.query(AllPersistenceIds)
      //#AllPersistenceIds
    }

    "demonstrate EventsByTag" in {
      //#EventsByTag
      import akka.persistence.query.EventsByTag
      implicit val mat = ActorMaterializer()(system)
      val queries = PersistenceQuery(system).readJournalFor(LeveldbReadJournal.Identifier)

      val src: Source[EventEnvelope, Unit] =
        queries.query(EventsByTag(tag = "green", offset = 0L))
      //#EventsByTag
    }

  }

}
