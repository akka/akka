/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.persistence.query.journal.leveldb

import akka.actor.ExtendedActorSystem
import akka.persistence.query.ReadJournalProvider
import com.typesafe.config.Config

class LeveldbReadJournalProvider(system: ExtendedActorSystem, config: Config) extends ReadJournalProvider {

  override val scaladslReadJournal: scaladsl.LeveldbReadJournal =
    new scaladsl.LeveldbReadJournal(system, config)

  override val javadslReadJournal: javadsl.LeveldbReadJournal =
    new javadsl.LeveldbReadJournal(scaladslReadJournal)

}
