/*
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.persistence.query

import akka.NotUsed
import akka.persistence.journal.{ EventAdapter, EventSeq }
import akka.testkit.AkkaSpec
import akka.persistence.query.{ EventEnvelope, PersistenceQuery, Sequence }
import akka.persistence.query.scaladsl._
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.persistence.journal.Tagged
import akka.stream.scaladsl.Source
import akka.stream.ActorMaterializer

import scala.annotation.tailrec

object LeveldbPersistenceQueryDocSpec {
  //#tagger
  import akka.persistence.journal.WriteEventAdapter
  import akka.persistence.journal.Tagged

  class MyTaggingEventAdapter extends WriteEventAdapter {
    val colors = Set("green", "black", "blue")
    override def toJournal(event: Any): Any = event match {
      case s: String =>
        var tags = colors.foldLeft(Set.empty[String]) { (acc, c) =>
          if (s.contains(c)) acc + c else acc
        }
        if (tags.isEmpty) event
        else Tagged(event, tags)
      case _ => event
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
      import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal

      val queries = PersistenceQuery(system).readJournalFor[LeveldbReadJournal](
        LeveldbReadJournal.Identifier)
      //#get-read-journal
    }

    "demonstrate EventsByPersistenceId" in {
      //#EventsByPersistenceId
      implicit val mat = ActorMaterializer()(system)
      val queries = PersistenceQuery(system).readJournalFor[LeveldbReadJournal](
        LeveldbReadJournal.Identifier)

      val src: Source[EventEnvelope, NotUsed] =
        queries.eventsByPersistenceId("some-persistence-id", 0L, Long.MaxValue)

      val events: Source[Any, NotUsed] = src.map(_.event)
      //#EventsByPersistenceId
    }

    "demonstrate AllPersistenceIds" in {
      //#AllPersistenceIds
      implicit val mat = ActorMaterializer()(system)
      val queries = PersistenceQuery(system).readJournalFor[LeveldbReadJournal](
        LeveldbReadJournal.Identifier)

      val src: Source[String, NotUsed] = queries.persistenceIds()
      //#AllPersistenceIds
    }

    "demonstrate EventsByTag" in {
      //#EventsByTag
      implicit val mat = ActorMaterializer()(system)
      val queries = PersistenceQuery(system).readJournalFor[LeveldbReadJournal](
        LeveldbReadJournal.Identifier)

      val src: Source[EventEnvelope, NotUsed] =
        queries.eventsByTag(tag = "green", offset = Sequence(0L))
      //#EventsByTag
    }

  }

}
