package docs.akka.persistence.typed

import akka.persistence.typed.ReplicaId
import akka.persistence.typed.scaladsl.{ ActiveActiveEventSourcing, EventSourcedBehavior }

object ActiveActiveCompileOnlySpec {

  //#replicas
  val DCA = ReplicaId("DC-A")
  val DCB = ReplicaId("DC-B")
  val AllReplicas = Set(DCA, DCB)
  //#replicas

  val queryPluginId = ""

  //#factory-shared
  ActiveActiveEventSourcing.withSharedJournal("entityId", DCA, AllReplicas, queryPluginId) { context =>
    EventSourcedBehavior(???, ???, ???, ???)
  }
  //#factory-shared

  //#factory
  ActiveActiveEventSourcing("entityId", DCA, Map(DCA -> "journalForDCA", DCB -> "journalForDCB")) { context =>
    EventSourcedBehavior(???, ???, ???, ???)
  }
  //#factory

}
