/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.query
import akka.actor.ExtendedActorSystem
import akka.persistence.query.ReadJournalProvider
import com.typesafe.config.Config

class PersistenceTestKitReadJournalProvider(system: ExtendedActorSystem, config: Config, configPath: String)
    extends ReadJournalProvider {

  override def scaladslReadJournal(): scaladsl.PersistenceTestKitReadJournal =
    new scaladsl.PersistenceTestKitReadJournal(system, config, configPath)

  override def javadslReadJournal(): javadsl.PersistenceTestKitReadJournal =
    new javadsl.PersistenceTestKitReadJournal(scaladslReadJournal())
}
