/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.persistence.query

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.typesafe.config.{ Config, ConfigFactory }

/**
 * Use for tests only!
 * Emits infinite stream of strings (representing queried for events).
 */
class DummyReadJournal extends scaladsl.ReadJournal with scaladsl.AllPersistenceIdsQuery {
  override def allPersistenceIds(): Source[String, NotUsed] =
    Source.fromIterator(() ⇒ Iterator.from(0)).map(_.toString)
}

object DummyReadJournal {
  final val Identifier = "akka.persistence.query.journal.dummy"
}

class DummyReadJournalForJava(readJournal: DummyReadJournal) extends javadsl.ReadJournal with javadsl.AllPersistenceIdsQuery {
  override def allPersistenceIds(): akka.stream.javadsl.Source[String, NotUsed] =
    readJournal.allPersistenceIds().asJava
}

object DummyReadJournalProvider {
  final val config: Config = ConfigFactory.parseString(
    s"""
      |${DummyReadJournal.Identifier} {
      |  class = "${classOf[DummyReadJournalProvider].getCanonicalName}"
      |}
    """.stripMargin)
}

class DummyReadJournalProvider extends ReadJournalProvider {

  override val scaladslReadJournal: DummyReadJournal =
    new DummyReadJournal

  override val javadslReadJournal: DummyReadJournalForJava =
    new DummyReadJournalForJava(scaladslReadJournal)
}
