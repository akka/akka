/*
 * Copyright (C) 2015-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query.journal.leveldb

import com.typesafe.config.Config

import akka.actor.ExtendedActorSystem
import akka.persistence.query.ReadJournalProvider

@deprecated("Use another journal/query implementation", "2.6.15")
class LeveldbReadJournalProvider(system: ExtendedActorSystem, config: Config) extends ReadJournalProvider {

  override val scaladslReadJournal: scaladsl.LeveldbReadJournal =
    new scaladsl.LeveldbReadJournal(system, config)

  override val javadslReadJournal: javadsl.LeveldbReadJournal =
    new javadsl.LeveldbReadJournal(scaladslReadJournal)

}
