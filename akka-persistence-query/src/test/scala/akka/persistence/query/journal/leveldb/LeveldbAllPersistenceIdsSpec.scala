/**
  * Copyright (C) 2015-2018 Lightbend Inc. <https://www.lightbend.com>
  */

package akka.persistence.query.journal.leveldb

import akka.persistence.query.PersistenceQuery
import akka.persistence.query.journal.AllPersistenceIdsSpec
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import com.typesafe.config.{Config, ConfigFactory}

object LeveldbAllPersistenceIdsSpec {
  def config: Config = ConfigFactory.parseString(
    """
    akka.persistence.journal.plugin = "akka.persistence.journal.leveldb"
    """).withFallback(AllPersistenceIdsSpec.config)
}

class LeveldbAllPersistenceIdsSpec extends AllPersistenceIdsSpec("Leveldb", LeveldbAllPersistenceIdsSpec.config) with Cleanup {
  override val queries = PersistenceQuery(system).readJournalFor[LeveldbReadJournal](LeveldbReadJournal.Identifier)
}
