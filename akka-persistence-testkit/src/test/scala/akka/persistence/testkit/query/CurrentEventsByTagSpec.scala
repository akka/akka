/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.query

import akka.Done
import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorRef
import akka.persistence.query.NoOffset
import akka.persistence.query.PersistenceQuery
import akka.persistence.testkit.query.EventsByPersistenceIdSpec.Command
import akka.persistence.testkit.query.EventsByPersistenceIdSpec.testBehaviour
import akka.persistence.testkit.query.scaladsl.PersistenceTestKitReadJournal
import akka.stream.scaladsl.Sink
import org.scalatest.wordspec.AnyWordSpecLike

class CurrentEventsByTagSpec
    extends ScalaTestWithActorTestKit(EventsByPersistenceIdSpec.config)
    with LogCapturing
    with AnyWordSpecLike {

  implicit val classic = system.classicSystem

  val queries =
    PersistenceQuery(system).readJournalFor[PersistenceTestKitReadJournal](PersistenceTestKitReadJournal.Identifier)

  def setup(persistenceId: String): ActorRef[Command] = {
    val probe = createTestProbe[Done]()
    val ref = setupEmpty(persistenceId)
    ref ! Command(s"$persistenceId-1", probe.ref)
    ref ! Command(s"$persistenceId-2", probe.ref)
    ref ! Command(s"$persistenceId-3", probe.ref)
    probe.expectMessage(Done)
    probe.expectMessage(Done)
    probe.expectMessage(Done)
    ref
  }

  def setupEmpty(persistenceId: String): ActorRef[Command] = {
    spawn(
      testBehaviour(persistenceId).withTagger(evt =>
        if (evt.indexOf('-') > 0) Set(evt.split('-')(1), "all")
        else Set("all")))
  }

  "Persistent test kit currentEventsByTag query" must {

    "find tagged events ordered by insert time" in {
      val probe = createTestProbe[Done]()
      val ref1 = setupEmpty("taggedpid-1")
      val ref2 = setupEmpty("taggedpid-2")
      ref1 ! Command("evt-1", probe.ref)
      ref1 ! Command("evt-2", probe.ref)
      ref1 ! Command("evt-3", probe.ref)
      probe.receiveMessages(3)
      ref2 ! Command("evt-4", probe.ref)
      probe.receiveMessage()
      ref1 ! Command("evt-5", probe.ref)
      probe.receiveMessage()

      queries.currentEventsByTag("all", NoOffset).runWith(Sink.seq).futureValue.map(_.event) should ===(
        Seq("evt-1", "evt-2", "evt-3", "evt-4", "evt-5"))
    }
  }

}
