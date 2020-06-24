/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.query
import akka.actor.ExtendedActorSystem
import akka.persistence.query.ReadJournalProvider

class PersistenceTestKitReadJournalProvider(system: ExtendedActorSystem) extends ReadJournalProvider {

  override def scaladslReadJournal(): scaladsl.PersistenceTestKitReadJournal =
    new scaladsl.PersistenceTestKitReadJournal(system)

  override def javadslReadJournal(): javadsl.PersistenceTestKitReadJournal =
    new javadsl.PersistenceTestKitReadJournal(scaladslReadJournal())
}
