/**
 * Copyright (C) 2015-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query.journal.leveldb

import akka.persistence.query.PersistenceQuery
import akka.persistence.query.journal.EventsByPersistenceIdSpec
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import com.typesafe.config.{ Config, ConfigFactory }

object LeveldbEventsByPersistenceIdSpec {
  def config: Config = ConfigFactory.parseString(
    """
    akka.persistence.journal.plugin = "akka.persistence.journal.leveldb"
    akka.persistence.journal.leveldb.dir = "target/journal-LeveldbEventsByPersistenceIdSpec"
    akka.persistence.query.journal.leveldb.refresh-interval = 1s
    """).withFallback(EventsByPersistenceIdSpec.config)
}

class LeveldbEventsByPersistenceIdSpec extends EventsByPersistenceIdSpec("Leveldb", LeveldbEventsByPersistenceIdSpec.config)
  with Cleanup {

  override val queries = PersistenceQuery(system).readJournalFor[LeveldbReadJournal](LeveldbReadJournal.Identifier)
}
