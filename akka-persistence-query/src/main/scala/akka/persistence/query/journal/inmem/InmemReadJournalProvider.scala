/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query.journal.inmem
import akka.actor.ExtendedActorSystem
import akka.persistence.query.ReadJournalProvider
import com.typesafe.config.Config

class InmemReadJournalProvider(system: ExtendedActorSystem, config: Config) extends ReadJournalProvider {

  override def scaladslReadJournal(): scaladsl.InmemReadJournal = new scaladsl.InmemReadJournal(system, config)

  override def javadslReadJournal(): javadsl.InmemReadJournal = new javadsl.InmemReadJournal(scaladslReadJournal())
}
