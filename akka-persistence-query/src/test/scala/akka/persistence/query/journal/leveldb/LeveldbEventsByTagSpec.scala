/**
  * Copyright (C) 2015-2018 Lightbend Inc. <https://www.lightbend.com>
  */

package akka.persistence.query.journal.leveldb

import akka.persistence.query.PersistenceQuery
import akka.persistence.query.journal.EventsByTagSpec
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import com.typesafe.config.{Config, ConfigFactory}

object LeveldbEventsByTagSpec {
  def config: Config = ConfigFactory.parseString(
    """
    akka.persistence.journal.plugin = "akka.persistence.journal.leveldb"
    akka.persistence.journal.leveldb {
      dir = "target/journal-LeveldbEventsByTagSpec"
      event-adapters {
        color-tagger  = akka.persistence.query.journal.ColorTagger
      }
      event-adapter-bindings = {
        "java.lang.String" = color-tagger
      }
    }
    akka.persistence.query.journal.leveldb.refresh-interval = 1s
    """).withFallback(EventsByTagSpec.config)

}

class LeveldbEventsByTagSpec extends EventsByTagSpec("Leveldb", LeveldbEventsByTagSpec.config)
  with Cleanup {

  override val queries = PersistenceQuery(system).readJournalFor[LeveldbReadJournal](LeveldbReadJournal.Identifier)
}
