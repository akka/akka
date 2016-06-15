package akka.persistence.query

import akka.actor.ActorSystem
import akka.persistence.PluginSpec
import akka.persistence.query.scaladsl._
import akka.stream.testkit.TestSubscriber
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.{ ActorMaterializer, Materializer }
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.concurrent.{ Eventually, ScalaFutures }

import scala.concurrent.Await
import scala.concurrent.duration.{ FiniteDuration, _ }

object QuerySpec {
  val config = ConfigFactory.parseString(
    """
    akka.persistence.publish-plugin-commands = on
    """)
}

abstract class QuerySpec(config: Config) extends PluginSpec(config) with ScalaFutures with Eventually {
  implicit lazy val system: ActorSystem = ActorSystem("JournalSpec", config.withFallback(QuerySpec.config))

  implicit val mat: Materializer = ActorMaterializer()(system)

  implicit val pc: PatienceConfig = PatienceConfig(timeout = 10.seconds)

  private var _qExtension: PersistenceQuery = _

  def qExtension: PersistenceQuery = _qExtension

  def readJournalPluginId: String

  def query[A <: scaladsl.ReadJournal]: A =
    qExtension.readJournalFor[A](readJournalPluginId)

  def persist(pid: String, tags: String*): String = {
    writeMessages(1, 1, pid, senderProbe.ref, writerUuid, tags: _*)
    pid
  }

  def persist(from: Int, to: Int, pid: String, tags: String*): String = {
    writeMessages(from, to, pid, senderProbe.ref, writerUuid, tags: _*)
    pid
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    _qExtension = PersistenceQuery(system)
  }

  def getAllPids: List[String] =
    query[CurrentPersistenceIdsQuery]
      .currentPersistenceIds()
      .runFold(List.empty[String])(_ :+ _)
      .futureValue

  def getEvents(pid: String): List[EventEnvelope] =
    query[CurrentEventsByPersistenceIdQuery]
      .currentEventsByPersistenceId(pid, 0, Long.MaxValue)
      .runFold(List.empty[EventEnvelope])(_ :+ _)
      .futureValue

  def withCurrentPersistenceIdsQuery(within: FiniteDuration = 10.seconds)(f: TestSubscriber.Probe[String] ⇒ Unit): Unit = {
    val tp = query[CurrentPersistenceIdsQuery].currentPersistenceIds().runWith(TestSink.probe[String])
    tp.within(within)(f(tp))
  }

  def withAllPersistenceIdsQuery(within: FiniteDuration = 10.seconds)(f: TestSubscriber.Probe[String] ⇒ Unit): Unit = {
    val tp = query[AllPersistenceIdsQuery].allPersistenceIds().runWith(TestSink.probe[String])
    tp.within(within)(f(tp))
  }

  def withCurrentEventsByPersistenceId(within: FiniteDuration = 10.seconds)(persistenceId: String, fromSequenceNr: Long = 0, toSequenceNr: Long = Long.MaxValue)(f: TestSubscriber.Probe[EventEnvelope] ⇒ Unit): Unit = {
    val tp = query[CurrentEventsByPersistenceIdQuery].currentEventsByPersistenceId(persistenceId, fromSequenceNr, toSequenceNr).runWith(TestSink.probe[EventEnvelope])
    tp.within(within)(f(tp))
  }

  def withEventsByPersistenceId(within: FiniteDuration = 10.seconds)(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long)(f: TestSubscriber.Probe[EventEnvelope] ⇒ Unit): Unit = {
    val tp = query[EventsByPersistenceIdQuery].eventsByPersistenceId(persistenceId, fromSequenceNr, toSequenceNr).runWith(TestSink.probe[EventEnvelope])
    tp.within(within)(f(tp))
  }

  def withCurrentEventsByTag(within: FiniteDuration = 10.seconds)(tag: String, offset: Long)(f: TestSubscriber.Probe[EventEnvelope] ⇒ Unit): Unit = {
    val tp = query[CurrentEventsByTagQuery].currentEventsByTag(tag, offset).runWith(TestSink.probe[EventEnvelope])
    tp.within(within)(f(tp))
  }

  def withEventsByTag(within: FiniteDuration = 10.seconds)(tag: String, offset: Long)(f: TestSubscriber.Probe[EventEnvelope] ⇒ Unit): Unit = {
    val tp = query[EventsByTagQuery].eventsByTag(tag, offset).runWith(TestSink.probe[EventEnvelope])
    tp.within(within)(f(tp))
  }
}
