/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.scaladsl

import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.persistence.testkit.PersistenceTestKitPlugin
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKitSpec.TestCounter
import akka.persistence.typed.PersistenceId
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

class EventSourcedBehaviorNoSnapshotTestKitSpec
    extends ScalaTestWithActorTestKit(ConfigFactory.parseString("""
    akka.persistence.testkit.events.serialize = off
    akka.persistence.testkit.snapshots.serialize = off
    """).withFallback(PersistenceTestKitPlugin.config))
    with AnyWordSpecLike
    with LogCapturing {

  "EventSourcedBehaviorTestKit" must {
    "not provide SnapshotTestKit if snapshots are not enabled" in {
      val eventSourcedTestKit = EventSourcedBehaviorTestKit[TestCounter.Command, TestCounter.Event, TestCounter.State](
        system,
        TestCounter(PersistenceId.ofUniqueId("test")))

      eventSourcedTestKit.snapshotTestKit shouldBe empty
    }
  }
}
