/**
 * Copyright (C) 2015-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query.journal.inmem

import akka.actor.ExtendedActorSystem
import akka.persistence.query.ReadJournalProvider
import com.typesafe.config.Config

class InmemReadJournalProvider(system: ExtendedActorSystem, config: Config) extends ReadJournalProvider {

  override val scaladslReadJournal: scaladsl.InmemReadJournal =
    new scaladsl.InmemReadJournal(system, config)

  override val javadslReadJournal: javadsl.InmemReadJournal =
    new javadsl.InmemReadJournal(scaladslReadJournal)

}
