/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.persistence.typed

import akka.persistence.typed.ReplicaId
import akka.persistence.typed.ReplicationId
import akka.persistence.typed.scaladsl.{ EventSourcedBehavior, ReplicatedEventSourcing }
import com.github.ghik.silencer.silent

@silent("never used")
object ReplicatedEventSourcingCompileOnlySpec {

  //#replicas
  val DCA = ReplicaId("DC-A")
  val DCB = ReplicaId("DC-B")
  val AllReplicas = Set(DCA, DCB)
  //#replicas

  val queryPluginId = ""

  trait Command
  trait State
  trait Event

  //#factory-shared
  ReplicatedEventSourcing.commonJournalConfig(
    ReplicationId("entityTypeHint", "entityId", DCA),
    AllReplicas,
    queryPluginId) { context =>
    EventSourcedBehavior[Command, State, Event](???, ???, ???, ???)
  }
  //#factory-shared

  //#factory
  ReplicatedEventSourcing.perReplicaJournalConfig(
    ReplicationId("entityTypeHint", "entityId", DCA),
    Map(DCA -> "journalForDCA", DCB -> "journalForDCB")) { context =>
    EventSourcedBehavior[Command, State, Event](???, ???, ???, ???)
  }
  //#factory

}
