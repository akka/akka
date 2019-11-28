/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.query.EventEnvelope
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.Sequence
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.persistence.typed.EventAdapter
import akka.persistence.typed.EventSeq
import akka.persistence.typed.PersistenceId
import akka.serialization.jackson.CborSerializable
import akka.stream.scaladsl.Sink
import akka.testkit.JavaSerializable
import com.typesafe.config.ConfigFactory
import org.scalatest.WordSpecLike

object EventSourcedEventAdapterSpec {

  private val conf = ConfigFactory.parseString(s"""
      akka.persistence.journal.leveldb.dir = "target/typed-persistence-${UUID.randomUUID().toString}"
      akka.persistence.journal.plugin = "akka.persistence.journal.leveldb"
    """)

  case class Wrapper(event: String) extends CborSerializable
  class WrapperEventAdapter extends EventAdapter[String, Wrapper] {
    override def toJournal(e: String): Wrapper = Wrapper("<" + e)
    override def fromJournal(p: Wrapper, manifest: String): EventSeq[String] = EventSeq.single(p.event + ">")
    override def manifest(event: String): String = ""
  }

  class FilterEventAdapter extends EventAdapter[String, String] {
    override def toJournal(e: String): String = e.toUpperCase()

    override def fromJournal(p: String, manifest: String): EventSeq[String] = {
      if (p == "B") EventSeq.empty
      else EventSeq.single(p)
    }

    override def manifest(event: String): String = ""
  }

  class SplitEventAdapter extends EventAdapter[String, String] {
    override def toJournal(e: String): String = e.toUpperCase()

    override def fromJournal(p: String, manifest: String): EventSeq[String] = {
      EventSeq(p.map("<" + _.toString + ">"))
    }

    override def manifest(event: String): String = ""
  }

  class EventAdapterWithManifest extends EventAdapter[String, String] {
    override def toJournal(e: String): String = e.toUpperCase()

    override def fromJournal(p: String, manifest: String): EventSeq[String] = {
      EventSeq.single(p + manifest)
    }

    override def manifest(event: String): String = event.length.toString
  }

  // generics doesn't work with Jackson, so using Java serialization
  case class GenericWrapper[T](event: T) extends JavaSerializable
  class GenericWrapperEventAdapter[T] extends EventAdapter[T, GenericWrapper[T]] {
    override def toJournal(e: T): GenericWrapper[T] = GenericWrapper(e)
    override def fromJournal(p: GenericWrapper[T], manifest: String): EventSeq[T] = EventSeq.single(p.event)
    override def manifest(event: T): String = ""
  }

}

class EventSourcedEventAdapterSpec
    extends ScalaTestWithActorTestKit(EventSourcedEventAdapterSpec.conf)
    with WordSpecLike
    with LogCapturing {
  import EventSourcedEventAdapterSpec._
  import EventSourcedBehaviorSpec.{
    counter,
    Command,
    Event,
    GetValue,
    Increment,
    IncrementWithPersistAll,
    Incremented,
    State
  }

  import akka.actor.typed.scaladsl.adapter._

  val pidCounter = new AtomicInteger(0)
  private def nextPid(): PersistenceId = PersistenceId.ofUniqueId(s"c${pidCounter.incrementAndGet()})")

  val queries: LeveldbReadJournal =
    PersistenceQuery(system.toClassic).readJournalFor[LeveldbReadJournal](LeveldbReadJournal.Identifier)

  private def behavior(pid: PersistenceId, probe: ActorRef[String]): EventSourcedBehavior[String, String, String] =
    EventSourcedBehavior(pid, "", commandHandler = { (_, command) =>
      Effect.persist(command).thenRun(newState => probe ! newState)
    }, eventHandler = { (state, evt) =>
      state + evt
    })

  "Event adapter" must {

    "wrap single events" in {
      val probe = TestProbe[String]()
      val pid = nextPid()
      val ref = spawn(behavior(pid, probe.ref).eventAdapter(new WrapperEventAdapter))

      ref ! "a"
      ref ! "b"
      probe.expectMessage("a")
      probe.expectMessage("ab")

      // replay
      val ref2 = spawn(behavior(pid, probe.ref).eventAdapter(new WrapperEventAdapter))
      ref2 ! "c"
      probe.expectMessage("<a><b>c")
    }

    "filter unused events" in {
      val probe = TestProbe[String]()
      val pid = nextPid()
      val ref = spawn(behavior(pid, probe.ref).eventAdapter(new FilterEventAdapter))

      ref ! "a"
      ref ! "b"
      ref ! "c"
      probe.expectMessage("a")
      probe.expectMessage("ab")
      probe.expectMessage("abc")

      // replay
      val ref2 = spawn(behavior(pid, probe.ref).eventAdapter(new FilterEventAdapter))
      ref2 ! "d"
      probe.expectMessage("ACd")
    }

    "split one event into several" in {
      val probe = TestProbe[String]()
      val pid = nextPid()
      val ref = spawn(behavior(pid, probe.ref).eventAdapter(new SplitEventAdapter))

      ref ! "a"
      ref ! "bc"
      probe.expectMessage("a")
      probe.expectMessage("abc")

      // replay
      val ref2 = spawn(behavior(pid, probe.ref).eventAdapter(new SplitEventAdapter))
      ref2 ! "d"
      probe.expectMessage("<A><B><C>d")
    }

    "support manifest" in {
      val probe = TestProbe[String]()
      val pid = nextPid()
      val ref = spawn(behavior(pid, probe.ref).eventAdapter(new EventAdapterWithManifest))

      ref ! "a"
      ref ! "bcd"
      probe.expectMessage("a")
      probe.expectMessage("abcd")

      // replay
      val ref2 = spawn(behavior(pid, probe.ref).eventAdapter(new EventAdapterWithManifest))
      ref2 ! "e"
      probe.expectMessage("A1BCD3e")
    }

    "adapt events" in {
      val pid = nextPid()
      val c = spawn(Behaviors.setup[Command] { ctx =>
        val persistentBehavior = counter(ctx, pid)

        persistentBehavior.eventAdapter(new GenericWrapperEventAdapter[Event])
      })
      val replyProbe = TestProbe[State]()

      c ! Increment
      c ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(1, Vector(0)))

      val events = queries.currentEventsByPersistenceId(pid.id).runWith(Sink.seq).futureValue
      events shouldEqual List(EventEnvelope(Sequence(1), pid.id, 1, GenericWrapper(Incremented(1))))

      val c2 =
        spawn(Behaviors.setup[Command](ctx => counter(ctx, pid).eventAdapter(new GenericWrapperEventAdapter[Event])))
      c2 ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(1, Vector(0)))

    }

    "adapter multiple events with persist all" in {
      val pid = nextPid()
      val c =
        spawn(Behaviors.setup[Command](ctx => counter(ctx, pid).eventAdapter(new GenericWrapperEventAdapter[Event])))
      val replyProbe = TestProbe[State]()

      c ! IncrementWithPersistAll(2)
      c ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(2, Vector(0, 1)))

      val events = queries.currentEventsByPersistenceId(pid.id).runWith(Sink.seq).futureValue
      events shouldEqual List(
        EventEnvelope(Sequence(1), pid.id, 1, GenericWrapper(Incremented(1))),
        EventEnvelope(Sequence(2), pid.id, 2, GenericWrapper(Incremented(1))))

      val c2 =
        spawn(Behaviors.setup[Command](ctx => counter(ctx, pid).eventAdapter(new GenericWrapperEventAdapter[Event])))
      c2 ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(2, Vector(0, 1)))
    }

    "adapt and tag events" in {
      val pid = nextPid()
      val c = spawn(Behaviors.setup[Command](ctx =>
        counter(ctx, pid).withTagger(_ => Set("tag99")).eventAdapter(new GenericWrapperEventAdapter[Event])))
      val replyProbe = TestProbe[State]()

      c ! Increment
      c ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(1, Vector(0)))

      val events = queries.currentEventsByPersistenceId(pid.id).runWith(Sink.seq).futureValue
      events shouldEqual List(EventEnvelope(Sequence(1), pid.id, 1, GenericWrapper(Incremented(1))))

      val c2 =
        spawn(Behaviors.setup[Command](ctx => counter(ctx, pid).eventAdapter(new GenericWrapperEventAdapter[Event])))
      c2 ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(State(1, Vector(0)))

      val taggedEvents = queries.currentEventsByTag("tag99").runWith(Sink.seq).futureValue
      taggedEvents shouldEqual List(EventEnvelope(Sequence(1), pid.id, 1, GenericWrapper(Incremented(1))))
    }
  }
}
