/*
 * Copyright (C) 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query.typed

import akka.actor.ExtendedActorSystem
import akka.persistence.query.ReadJournalProvider
import com.typesafe.config.Config

final class EventsBySliceFirehoseReadJournalProvider(system: ExtendedActorSystem, config: Config, cfgPath: String)
    extends ReadJournalProvider {

  private lazy val scaladslReadJournalInstance: scaladsl.EventsBySliceFirehoseQuery =
    new scaladsl.EventsBySliceFirehoseQuery(system, config, cfgPath)

  override def scaladslReadJournal(): scaladsl.EventsBySliceFirehoseQuery = scaladslReadJournalInstance

  private lazy val javadslReadJournalInstance =
    new javadsl.EventsBySliceFirehoseQuery(new scaladsl.EventsBySliceFirehoseQuery(system, config, cfgPath))

  override def javadslReadJournal(): javadsl.EventsBySliceFirehoseQuery = javadslReadJournalInstance
}
