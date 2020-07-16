/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.persistence.typed

import akka.persistence.typed.ReplicaId
import akka.persistence.typed.scaladsl.{ActiveActiveEventSourcing, EventSourcedBehavior}
import com.github.ghik.silencer.silent

@silent("never used")
object ActiveActiveCompileOnlySpec {

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
  ActiveActiveEventSourcing.withSharedJournal("entityId", DCA, AllReplicas, queryPluginId) { context =>
    EventSourcedBehavior[Command, State, Event](???, ???, ???, ???)
  }
  //#factory-shared

  //#factory
  ActiveActiveEventSourcing("entityId", DCA, Map(DCA -> "journalForDCA", DCB -> "journalForDCB")) { context =>
    EventSourcedBehavior[Command, State, Event](???, ???, ???, ???)
  }
  //#factory

}
