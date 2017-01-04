/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.persistence.query

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.typesafe.config.{ Config, ConfigFactory }
import akka.actor.ExtendedActorSystem

/**
 * Use for tests only!
 * Emits infinite stream of strings (representing queried for events).
 */
class DummyReadJournal extends scaladsl.ReadJournal with scaladsl.PersistenceIdsQuery {
  override def persistenceIds(): Source[String, NotUsed] =
    Source.fromIterator(() ⇒ Iterator.from(0)).map(_.toString)
}

object DummyReadJournal {
  final val Identifier = "akka.persistence.query.journal.dummy"
}

class DummyReadJournalForJava(readJournal: DummyReadJournal) extends javadsl.ReadJournal with javadsl.PersistenceIdsQuery {
  override def persistenceIds(): akka.stream.javadsl.Source[String, NotUsed] =
    readJournal.persistenceIds().asJava
}

object DummyReadJournalProvider {
  final val config: Config = ConfigFactory.parseString(
    s"""
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
    """)
}

class DummyReadJournalProvider extends ReadJournalProvider {

  override val scaladslReadJournal: DummyReadJournal =
    new DummyReadJournal

  override val javadslReadJournal: DummyReadJournalForJava =
    new DummyReadJournalForJava(scaladslReadJournal)
}

class DummyReadJournalProvider2(sys: ExtendedActorSystem) extends DummyReadJournalProvider

class DummyReadJournalProvider3(sys: ExtendedActorSystem, conf: Config) extends DummyReadJournalProvider

class DummyReadJournalProvider4(sys: ExtendedActorSystem, conf: Config, confPath: String) extends DummyReadJournalProvider

