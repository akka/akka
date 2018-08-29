/**
 * Copyright (C) 2015-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query.journal.inmem

import akka.persistence.query.PersistenceQuery
import akka.persistence.query.journal.AllPersistenceIdsSpec
import akka.persistence.query.journal.inmem.scaladsl.InmemReadJournal
import com.typesafe.config.{ Config, ConfigFactory }

object InmemAllPersistenceIdsSpec {
  def config: Config = ConfigFactory.parseString(
    """
    akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
    """).withFallback(AllPersistenceIdsSpec.config)
}

class InmemAllPersistenceIdsSpec extends AllPersistenceIdsSpec("Inmem", InmemAllPersistenceIdsSpec.config) {
  override val queries = PersistenceQuery(system).readJournalFor[InmemReadJournal](InmemReadJournal.Identifier)
}
