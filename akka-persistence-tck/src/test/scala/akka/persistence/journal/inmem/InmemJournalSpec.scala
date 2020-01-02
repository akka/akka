/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.journal.inmem

import akka.persistence.CapabilityFlag
import akka.persistence.PersistenceSpec
import akka.persistence.journal.JournalSpec

class InmemJournalSpec extends JournalSpec(config = PersistenceSpec.config("inmem", "InmemJournalSpec")) {
  override protected def supportsRejectingNonSerializableObjects: CapabilityFlag = CapabilityFlag.off()
}
