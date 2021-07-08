/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.query

import akka.actor.ExtendedActorSystem
import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.persistence.query.NoOffset
import akka.persistence.query.Sequence
import akka.persistence.query.DurableStateChange
import akka.persistence.testkit.state.scaladsl.PersistenceTestKitDurableStateStore
import akka.persistence.testkit.PersistenceTestKitDurableStateStorePlugin
import akka.stream.testkit.scaladsl.TestSink

import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

object PersistenceTestKitDurableStateStoreSpec {
  val config =
    PersistenceTestKitDurableStateStorePlugin.config.withFallback(ConfigFactory.parseString("""
    akka.loglevel = DEBUG
      """))
  case class Record(id: Int, name: String)
}

class PersistenceTestKitDurableStateStoreSpec
    extends ScalaTestWithActorTestKit(PersistenceTestKitDurableStateStoreSpec.config)
    with LogCapturing
    with AnyWordSpecLike {

  import PersistenceTestKitDurableStateStoreSpec._

  implicit val classic = system.classicSystem

  "Persistent test kit state store changes query" must {

    "find tagged state changes ordered by upsert" in {
      val stateStore = new PersistenceTestKitDurableStateStore[Record](classic.asInstanceOf[ExtendedActorSystem])
      val record = Record(1, "name-1")
      val recordChange = Record(1, "my-name-1")
      val tag = "tag-1"
      stateStore.upsertObject("record-1", 1L, record, tag)
      val testSink = stateStore.changes(tag, NoOffset).runWith(TestSink[DurableStateChange[Record]]())

      val firstStateChange = testSink.request(1).expectNext()
      firstStateChange.value should be(record)
      firstStateChange.revision should be(1L)

      stateStore.upsertObject("record-1", 2L, recordChange, tag)
      val secondStateChange = testSink.request(1).expectNext()
      secondStateChange.value should be(recordChange)
      secondStateChange.revision should be(2L)
      assert(
        secondStateChange.offset.asInstanceOf[Sequence].value > firstStateChange.offset.asInstanceOf[Sequence].value)
    }

    "find tagged current state changes ordered by upsert" in {
      val stateStore = new PersistenceTestKitDurableStateStore[Record](classic.asInstanceOf[ExtendedActorSystem])
      val record = Record(1, "name-1")
      val tag = "tag-1"

      stateStore.upsertObject("record-1", 1L, record, tag)

      val testSinkCurrentChanges =
        stateStore.currentChanges(tag, NoOffset).runWith(TestSink[DurableStateChange[Record]]())

      stateStore.upsertObject("record-1", 2L, record.copy(name = "my-name-1-2"), tag)
      stateStore.upsertObject("record-1", 3L, record.copy(name = "my-name-1-3"), tag)

      val currentStateChange = testSinkCurrentChanges.request(1).expectNext()

      currentStateChange.value should be(record)
      currentStateChange.revision should be(1L)
      testSinkCurrentChanges.request(1).expectComplete()

      val testSinkIllegalOffset =
        stateStore.currentChanges(tag, Sequence(100L)).runWith(TestSink[DurableStateChange[Record]]())
      testSinkIllegalOffset.request(1).expectNoMessage()
    }
  }
}
