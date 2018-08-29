/**
 * Copyright (C) 2015-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query.journal.inmem

import akka.persistence.query.PersistenceQuery
import akka.persistence.query.journal.EventsByTagSpec
import akka.persistence.query.journal.inmem.scaladsl.InmemReadJournal
import com.typesafe.config.{ Config, ConfigFactory }

object InmemEventsByTagSpec {
  def config: Config = ConfigFactory.parseString(
    """
    akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
    akka.persistence.journal.inmem {
      event-adapters {
        color-tagger  = akka.persistence.query.journal.ColorTagger
      }
      event-adapter-bindings = {
        "java.lang.String" = color-tagger
      }
    }
    """).withFallback(EventsByTagSpec.config)

}

class InmemEventsByTagSpec extends EventsByTagSpec("Inmem", InmemEventsByTagSpec.config) {
  override val queries = PersistenceQuery(system).readJournalFor[InmemReadJournal](InmemReadJournal.Identifier)
}
