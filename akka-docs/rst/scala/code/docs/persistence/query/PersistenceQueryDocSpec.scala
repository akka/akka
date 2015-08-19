/*
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.persistence.query

import akka.actor._
import akka.persistence.query.scaladsl.ReadJournal
import akka.persistence.{ Recovery, PersistentActor }
import akka.persistence.query._
import akka.stream.{ FlowShape, ActorMaterializer }
import akka.stream.scaladsl.FlowGraph
import akka.stream.scaladsl.{ Flow, Sink, Source }
import akka.testkit.AkkaSpec
import akka.util.Timeout
import docs.persistence.query.PersistenceQueryDocSpec.{ DummyStore, TheOneWhoWritesToQueryJournal }
import org.reactivestreams.Subscriber
import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._
import com.typesafe.config.Config

object PersistenceQueryDocSpec {

  implicit val timeout = Timeout(3.seconds)

  //#my-read-journal
  class MyReadJournal(system: ExtendedActorSystem, config: Config) extends ReadJournal {

    private val defaulRefreshInterval = 3.seconds

    override def query[T, M](q: Query[T, M], hints: Hint*): Source[T, M] =
      q match {
        case EventsByTag(tag, offset) ⇒
          val props = MyEventsByTagPublisher.props(tag, offset, refreshInterval(hints))
          Source.actorPublisher[EventEnvelope](props)
            .mapMaterializedValue(_ ⇒ noMaterializedValue)

        case unsupported ⇒
          Source.failed[T](
            new UnsupportedOperationException(
              s"Query $unsupported not supported by ${getClass.getName}"))
            .mapMaterializedValue(_ ⇒ noMaterializedValue)
      }

    private def refreshInterval(hints: Seq[Hint]): FiniteDuration =
      hints.collectFirst { case RefreshInterval(interval) ⇒ interval }
        .getOrElse(defaulRefreshInterval)

    private def noMaterializedValue[M]: M =
      null.asInstanceOf[M]
  }

  //#my-read-journal
  case class ComplexState() {
    def readyToSave = false
  }
  case class Record(any: Any)
  class DummyStore { def save(record: Record) = Future.successful(42L) }

  class X {
    val JournalId = ""

    def convertToReadSideTypes(in: Any): Any = ???

    object ReactiveStreamsCompatibleDBDriver {
      def batchWriter: Subscriber[immutable.Seq[Any]] = ???
    }

    //#projection-into-different-store-rs
    implicit val system = ActorSystem()
    implicit val mat = ActorMaterializer()

    val readJournal = PersistenceQuery(system).readJournalFor(JournalId)
    val dbBatchWriter: Subscriber[immutable.Seq[Any]] =
      ReactiveStreamsCompatibleDBDriver.batchWriter

    // Using an example (Reactive Streams) Database driver
    readJournal
      .query(EventsByPersistenceId("user-1337"))
      .map(envelope => envelope.event)
      .map(convertToReadSideTypes) // convert to datatype
      .grouped(20) // batch inserts into groups of 20
      .runWith(Sink(dbBatchWriter)) // write batches to read-side database
    //#projection-into-different-store-rs
  }

  //#projection-into-different-store-actor
  class TheOneWhoWritesToQueryJournal(id: String) extends Actor {
    val store = new DummyStore()

    var state: ComplexState = ComplexState()

    def receive = {
      case m =>
        state = updateState(state, m)
        if (state.readyToSave) store.save(Record(state))
    }

    def updateState(state: ComplexState, msg: Any): ComplexState = {
      // some complicated aggregation logic here ...
      state
    }
  }

  //#projection-into-different-store-actor

}

class PersistenceQueryDocSpec(s: String) extends AkkaSpec(s) {

  def this() {
    this(
      """
        akka.persistence.query.noop-read-journal {
          class = "docs.persistence.query.NoopReadJournal"
        }
      """.stripMargin)
  }

  //#basic-usage
  // obtain read journal by plugin id
  val readJournal =
    PersistenceQuery(system).readJournalFor("akka.persistence.query.noop-read-journal")

  // issue query to journal
  val source: Source[EventEnvelope, Unit] =
    readJournal.query(EventsByPersistenceId("user-1337", 0, Long.MaxValue))

  // materialize stream, consuming events
  implicit val mat = ActorMaterializer()
  source.runForeach { event => println("Event: " + event) }
  //#basic-usage

  //#all-persistence-ids-live
  readJournal.query(AllPersistenceIds)
  //#all-persistence-ids-live

  //#all-persistence-ids-snap
  readJournal.query(AllPersistenceIds, hints = NoRefresh)
  //#all-persistence-ids-snap

  //#events-by-tag
  // assuming journal is able to work with numeric offsets we can:

  val blueThings: Source[EventEnvelope, Unit] =
    readJournal.query(EventsByTag("blue"))

  // find top 10 blue things:
  val top10BlueThings: Future[Vector[Any]] =
    blueThings
      .map(_.event)
      .take(10) // cancels the query stream after pulling 10 elements
      .runFold(Vector.empty[Any])(_ :+ _)

  // start another query, from the known offset
  val furtherBlueThings = readJournal.query(EventsByTag("blue", offset = 10))
  //#events-by-tag

  //#events-by-persistent-id-refresh
  readJournal.query(EventsByPersistenceId("user-us-1337"), hints = RefreshInterval(1.second))

  //#events-by-persistent-id-refresh

  //#advanced-journal-query-definition
  final case class RichEvent(tags: immutable.Set[String], payload: Any)

  case class QueryStats(totalEvents: Long)

  case class ByTagsWithStats(tags: immutable.Set[String])
    extends Query[RichEvent, QueryStats]

  //#advanced-journal-query-definition

  //#advanced-journal-query-hints

  import scala.concurrent.duration._

  readJournal.query(EventsByTag("blue"), hints = RefreshInterval(1.second))
  //#advanced-journal-query-hints

  //#advanced-journal-query-usage
  val query: Source[RichEvent, QueryStats] =
    readJournal.query(ByTagsWithStats(Set("red", "blue")))

  query
    .mapMaterializedValue { stats => println(s"Stats: $stats") }
    .map { event => println(s"Event payload: ${event.payload}") }
    .runWith(Sink.ignore)

  //#advanced-journal-query-usage

  //#materialized-query-metadata
  // a plugin can provide:
  case class QueryMetadata(deterministicOrder: Boolean, infinite: Boolean)

  case object AllEvents extends Query[Any, QueryMetadata]

  val events = readJournal.query(AllEvents)
  events
    .mapMaterializedValue { meta =>
      println(s"The query is: " +
        s"ordered deterministically: ${meta.deterministicOrder}, " +
        s"infinite: ${meta.infinite}")
    }

  //#materialized-query-metadata

  //#projection-into-different-store
  class MyResumableProjection(name: String) {
    def saveProgress(offset: Long): Future[Long] = ???
    def latestOffset: Future[Long] = ???
  }
  //#projection-into-different-store

  class RunWithActor {
    //#projection-into-different-store-actor-run
    import akka.pattern.ask
    import system.dispatcher
    implicit val timeout = Timeout(3.seconds)

    val bidProjection = new MyResumableProjection("bid")

    val writerProps = Props(classOf[TheOneWhoWritesToQueryJournal], "bid")
    val writer = system.actorOf(writerProps, "bid-projection-writer")

    bidProjection.latestOffset.foreach { startFromOffset =>
      readJournal
        .query(EventsByTag("bid", startFromOffset))
        .mapAsync(8) { envelope => (writer ? envelope.event).map(_ => envelope.offset) }
        .mapAsync(1) { offset => bidProjection.saveProgress(offset) }
        .runWith(Sink.ignore)
    }
    //#projection-into-different-store-actor-run
  }

  class RunWithAsyncFunction {
    //#projection-into-different-store-simple
    trait ExampleStore {
      def save(event: Any): Future[Unit]
    }
    //#projection-into-different-store-simple

    //#projection-into-different-store-simple
    val store: ExampleStore = ???

    readJournal
      .query(EventsByTag("bid"))
      .mapAsync(1) { e => store.save(e) }
      .runWith(Sink.ignore)
    //#projection-into-different-store-simple
  }

}

class NoopReadJournal(sys: ExtendedActorSystem) extends ReadJournal {
  override def query[T, M](q: Query[T, M], hints: Hint*): Source[T, M] =
    Source.empty.mapMaterializedValue(_ => null.asInstanceOf[M])
}
