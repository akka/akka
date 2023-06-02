/*
 * Copyright (C) 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query.typed

import akka.actor.ExtendedActorSystem
import akka.persistence.query.ReadJournalProvider
import com.typesafe.config.Config

final class EventsBySliceFirehoseReadJournalProvider(system: ExtendedActorSystem, config: Config, cfgPath: String)
    extends ReadJournalProvider {

  private lazy val scaladslReadJournalInstance: scaladsl.EventsBySliceFirehoseReadJournal =
    new scaladsl.EventsBySliceFirehoseReadJournal(system, config, cfgPath)

  override def scaladslReadJournal(): scaladsl.EventsBySliceFirehoseReadJournal = scaladslReadJournalInstance

  private lazy val javadslReadJournalInstance =
    new javadsl.EventsBySliceFirehoseReadJournal(new scaladsl.EventsBySliceFirehoseReadJournal(system, config, cfgPath))

  override def javadslReadJournal(): javadsl.EventsBySliceFirehoseReadJournal = javadslReadJournalInstance
}
