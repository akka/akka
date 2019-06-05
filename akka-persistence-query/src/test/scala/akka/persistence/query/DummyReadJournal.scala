/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.typesafe.config.{ Config, ConfigFactory }
import akka.actor.ExtendedActorSystem
import akka.util.unused

/**
 * Use for tests only!
 * Emits infinite stream of strings (representing queried for events).
 */
class DummyReadJournal(val dummyValue: String) extends scaladsl.ReadJournal with scaladsl.PersistenceIdsQuery {
  override def persistenceIds(): Source[String, NotUsed] =
    Source.fromIterator(() => Iterator.from(0)).map(_.toString)
}

object DummyReadJournal {
  final val Identifier = "akka.persistence.query.journal.dummy"
}

class DummyReadJournalForJava(readJournal: DummyReadJournal)
    extends javadsl.ReadJournal
    with javadsl.PersistenceIdsQuery {
  override def persistenceIds(): akka.stream.javadsl.Source[String, NotUsed] =
    readJournal.persistenceIds().asJava
}

object DummyReadJournalProvider {
  final val config: Config = ConfigFactory.parseString(s"""
      ${DummyReadJournal.Identifier} {
        class = "${classOf[DummyReadJournalProvider].getCanonicalName}"
      }
      ${DummyReadJournal.Identifier}2 {
        class = "${classOf[DummyReadJournalProvider2].getCanonicalName}"
      }
      ${DummyReadJournal.Identifier}3 {
        class = "${classOf[DummyReadJournalProvider3].getCanonicalName}"
      }
      ${DummyReadJournal.Identifier}4 {
        class = "${classOf[DummyReadJournalProvider4].getCanonicalName}"
      }
      ${DummyReadJournal.Identifier}5 {
        class = "${classOf[DummyReadJournalProvider5].getCanonicalName}"
      }
    """)
}

class DummyReadJournalProvider(dummyValue: String) extends ReadJournalProvider {

  // mandatory zero-arg constructor
  def this() = this("dummy")

  override val scaladslReadJournal: DummyReadJournal =
    new DummyReadJournal(dummyValue)

  override val javadslReadJournal: DummyReadJournalForJava =
    new DummyReadJournalForJava(scaladslReadJournal)
}

class DummyReadJournalProvider2(@unused sys: ExtendedActorSystem) extends DummyReadJournalProvider

class DummyReadJournalProvider3(@unused sys: ExtendedActorSystem, @unused conf: Config) extends DummyReadJournalProvider

class DummyReadJournalProvider4(@unused sys: ExtendedActorSystem, @unused conf: Config, @unused confPath: String)
    extends DummyReadJournalProvider

class DummyReadJournalProvider5(@unused sys: ExtendedActorSystem) extends DummyReadJournalProvider

class CustomDummyReadJournalProvider5(@unused sys: ExtendedActorSystem) extends DummyReadJournalProvider("custom")
