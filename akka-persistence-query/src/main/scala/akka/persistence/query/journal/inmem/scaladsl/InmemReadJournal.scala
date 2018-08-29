package akka.persistence.query.journal.inmem.scaladsl

import java.util.concurrent.{CompletionStage, TimeUnit}

import akka.{Done, NotUsed}
import akka.pattern.ask
import akka.actor.{Actor, ActorLogging, ExtendedActorSystem, Props}
import akka.event.Logging
import akka.persistence.journal.Tagged
import akka.persistence.{Persistence, PersistentRepr}
import akka.persistence.journal.inmem.InmemJournal
import akka.persistence.query._
import akka.persistence.query.journal.inmem.scaladsl.MessageReceiver.{LiveSubscription, SubscribeCurrent, SubscribeLive}
import akka.persistence.query.scaladsl._
import akka.stream._
import akka.stream.scaladsl.SourceQueueWithComplete
import akka.stream.scaladsl.{Concat, Flow, GraphDSL, Keep, RunnableGraph, Sink, Source, Zip}
import akka.stream.stage.{GraphStage, GraphStageLogic, OutHandler}
import akka.util.Timeout
import com.typesafe.config.Config
import org.reactivestreams.{Publisher, Subscriber}

import scala.collection.mutable
import scala.collection.{immutable => im}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.{Duration, FiniteDuration}


object MessageReceiver {
  trait Get
  case object GetAllCurrent /* response is: Seq[PersistentRepr] */ extends Get
  final case class GetAllCurrentTagged(tag: String) /* response is: Seq[(PersistentRepr, Offset)] */ extends Get


  final case class LiveSubscription(source: Source[PersistentRepr, NotUsed])
  final case object SubscribeLive // response is: LiveSubscription */
  final case object SubscribeCurrent // response is: LiveSubscription */
}

class MessageReceiver(implicit mat: Materializer) extends Actor with ActorLogging {
  implicit def executionContext:ExecutionContext = context.dispatcher

  private var allMessages: mutable.Buffer[PersistentRepr] = mutable.Buffer.empty
  private var taggedMessages: mutable.Map[String, mutable.Buffer[PersistentRepr]] = mutable.Map.empty

  private def createCurrentSource: Source[PersistentRepr, NotUsed] = {
    val currentDataSnapshot = allMessages.toStream
    val current = Source(currentDataSnapshot)
    current
  }

  private def createNewAndCurrentLiveSource: (SourceQueueWithComplete[PersistentRepr], Source[PersistentRepr, NotUsed]) = {
    /* This first loop runs immediately, and publishes into a Backpressured-publisher */
    val live = Source.queue[PersistentRepr](10, OverflowStrategies.Backpressure)
    /* the source we return is a combination of current and live data sources */
    val current = createCurrentSource
    val combo = Source.combineMat(current, live)(Concat(_))(Keep.right)

    val runnable = combo.toMat(Sink.asPublisher(false))(Keep.both)

    val (sourceQ, pub) = runnable.run

    val livePub = Source.fromPublisher(pub)
        .watchTermination() {
      case (notUsed, fDone) =>
        /* propagate completion, especially early termination of the downstream graph, to the upstream graph */
        fDone
            .map { _ =>
              log.debug("completing {} due to downstream being terminated", sourceQ)
              sourceQ.complete()
            }
            .onFailure {
              case ex: Throwable =>
                sourceQ.fail(ex)
            }

        notUsed
    }

    (sourceQ, livePub)
  }

  private var activePublishedQueues: mutable.Buffer[SourceQueueWithComplete[PersistentRepr]] = mutable.Buffer.empty

  private def startNewAndCurrentLiveSource: Source[PersistentRepr, NotUsed] = {
    val (sourceQ, source) = createNewAndCurrentLiveSource
    activePublishedQueues.append(sourceQ)
    source
  }

  private def publishMessageSynchronously(m: PersistentRepr): Unit = {
    if (activePublishedQueues.nonEmpty) {
      try {
        val fFutureOfferResults = activePublishedQueues
          .map { q =>
            val f = try {
              q.offer(m)
            } catch {
              case ex: Throwable =>
                Future.successful(QueueOfferResult.Failure(ex))
            }

            f.map(r => (m, q, r))
              .recoverWith { case ex: Throwable =>
                Future.successful((m, q, QueueOfferResult.Failure(ex)))
              }
          }


        val fOfferResults = try {
          Future.sequence(fFutureOfferResults)
        } catch {
          case t: Throwable =>
            throw t
        }

        val enqueueResults = try {
          Await.result(fOfferResults, Duration.Inf) // we voluntarily wait indefinitely until it no longer backpressures
          //.map { r => println(s"enqueue result=${r} "); r }
        } catch {
          case t: Throwable =>
            throw t
        }

        val queuesToPurge = enqueueResults
          .flatMap {
            case (m, q, QueueOfferResult.Enqueued) =>
              None
            case (m, q, QueueOfferResult.Dropped) =>
              q.fail(new IllegalStateException("downstream queues should not drop items, killing live Source"))
              Some(q)
            case (m, q, QueueOfferResult.Failure(cause)) =>
              q.fail(new IllegalStateException("downstream queue failed, killing live Source", cause))
              Some(q)
            case (m, q, QueueOfferResult.QueueClosed) =>
              Some(q) // we reached an end and this is okay
          }.toSet

        if (queuesToPurge.nonEmpty) {
          val newPublishedQueues = activePublishedQueues.filterNot(queuesToPurge.contains)
          activePublishedQueues = newPublishedQueues
        }
      }
      catch {
        case t: Throwable =>
          log.error(t, "failing to propagate {}", m)
      }
    }
  }

  /**
    * Scala API: This defines the initial actor behavior, it must return a partial function
    * with the actor logic.
    */
  def receive = {
    case InmemJournal.SpooledMessages(messages) =>
      allMessages = allMessages ++ messages
      messages.foreach(publishMessageSynchronously)

    case InmemJournal.DeleteSpooledMessages(persistenceId, toSequenceNr) =>
      val kept = allMessages.filterNot { repr => (repr.persistenceId == persistenceId) && (repr.sequenceNr <= toSequenceNr) }
      allMessages = kept
      sender() ! Done

    case MessageReceiver.GetAllCurrent =>
      sender() ! allMessages.toArray.toSeq

    case MessageReceiver.GetAllCurrentTagged(tag) =>

      val perTag: Seq[(PersistentRepr, Offset)] = allMessages
        .map(repr => (repr, repr.payload))
        .collect { case (repr, Tagged(realPayload, tags)) if tags.contains(tag) => repr }
        .toArray
        .zipWithIndex
        .map { case (repr, idx) => (repr, Sequence(idx + 1)) }


      sender() ! perTag

    case MessageReceiver.SubscribeLive =>
      val newSrc = startNewAndCurrentLiveSource
      sender() ! MessageReceiver.LiveSubscription(newSrc)

    case MessageReceiver.SubscribeCurrent =>
      val newSrc = createCurrentSource
      sender() ! MessageReceiver.LiveSubscription(newSrc)
  }
}

/**
  * Scala API [[akka.persistence.query.scaladsl.ReadJournal]] implementation for Inmem (for testing purposes only)
  *
  * It is retrieved with:
  * {{{
  * val queries = PersistenceQuery(system).readJournalFor[InmemReadJournal](InmemReadJournal.Identifier)
  * }}}
  *
  * Corresponding Java API is in [[akka.persistence.query.journal.inmem.javadsl.InmemReadJournal]].
  *
  * Configuration settings can be defined in the configuration section with the
  * absolute path corresponding to the identifier, which is `"akka.persistence.query.journal.inmem"`
  * for the default [[InmemReadJournal#Identifier]]. See `reference.conf`.
  */
class InmemReadJournal(system: ExtendedActorSystem, config: Config) extends ReadJournal
  with PersistenceIdsQuery with CurrentPersistenceIdsQuery
  with EventsByPersistenceIdQuery with CurrentEventsByPersistenceIdQuery
  with EventsByTagQuery with CurrentEventsByTagQuery {

  implicit def timeout: Timeout = Timeout(FiniteDuration(1, TimeUnit.MINUTES))

  implicit def executionContext: ExecutionContext = system.dispatcher

  implicit val mat: ActorMaterializer = ActorMaterializer()(system)

  private val journalRef = Persistence(system).journalFor("akka.persistence.journal.inmem")
  private val receiver = system.actorOf(Props(new MessageReceiver), "inmem-journal-recv")

  journalRef ! InmemJournal.SetDownstreamReceiver(receiver)
  /* from this point on, receiver receives a copy of 'persisted' events */

  private def getAllCurrentRepr(filterPredicate: (PersistentRepr, Offset) => Boolean = (_,_) => true): Future[Seq[(PersistentRepr, Offset)]] =
    (receiver ? MessageReceiver.GetAllCurrent)
      .map { case pack: Seq[PersistentRepr @unchecked] =>
        pack
          .filter { item => filterPredicate(item, NoOffset) }
          .map(item => (item, NoOffset))
      }

  private def getAllTaggedRepr(tag: String, filterPredicate: (PersistentRepr, Offset) => Boolean = (_,_) => true): Future[Seq[(PersistentRepr, Offset)]] = {
    val filtered =
      (receiver ? MessageReceiver.GetAllCurrentTagged(tag))
        .map { case pack: Seq[(PersistentRepr, Offset)@unchecked] =>
          pack
            .filter { case (item, offset) => filterPredicate(item, offset) }
        }
    /* now strip the "Tagged" structure, as the current API doesn't provide for conjunction of tags */

    filtered.map { pack =>
      pack
        .map { case (repr, idx) => (repr, repr.payload, idx) }
        .collect {
          case (repr, Tagged(realPayload, tags), idx) =>
            (repr.withPayload(realPayload), idx)
        }
    }
  }

  private def currentRepr(f: Future[Iterable[(PersistentRepr, Offset)]]): Source[EventEnvelope, NotUsed] =
    Source.fromFuture(f).flatMapConcat {
      iterable =>
        Source.fromIterator {
          () =>
            iterable.map {
              case (repr, offset) =>
                val event = repr.payload // FIXME: not entirely sure?
                EventEnvelope(offset, repr.persistenceId, repr.sequenceNr, event)
            }.toIterator
        }
    }

  private val streamLogLevels =       Attributes.logLevels(onElement = Logging.InfoLevel, onFailure = Logging.ErrorLevel, onFinish = Logging.InfoLevel)

  private def newLiveMessageSource: Source[PersistentRepr, NotUsed] = {
    val f = Source.fromFuture((receiver ? SubscribeLive).mapTo[LiveSubscription].map(_.source))
      .withAttributes(streamLogLevels)

    val f2 = f.flatMapConcat(identity)

    f2
      .withAttributes(streamLogLevels)
  }

  private def newCurrentMessageSource: Source[PersistentRepr, NotUsed] = {
    val f = Source.fromFuture((receiver ? SubscribeCurrent).mapTo[LiveSubscription].map(_.source))
      .withAttributes(streamLogLevels)

    val f2 = f.flatMapConcat(identity)


    f2
      .withAttributes(streamLogLevels)
  }

  /**
    * Query all `PersistentActor` identifiers, i.e. as defined by the
    * `persistenceId` of the `PersistentActor`.
    *
    * The stream is not completed when it reaches the end of the currently used `persistenceIds`,
    * but it continues to push new `persistenceIds` when new persistent actors are created.
    * Corresponding query that is completed when it reaches the end of the currently
    * currently used `persistenceIds` is provided by [[CurrentPersistenceIdsQuery#currentPersistenceIds]].
    */
  override def persistenceIds(): Source[String, NotUsed] = {
    reprSourceToPersistenceIds(newLiveMessageSource)
  }

  /**
    * Same type of query as [[PersistenceIdsQuery#persistenceIds]] but the stream
    * is completed immediately when it reaches the end of the "result set". Persistent
    * actors that are created after the query is completed are not included in the stream.
    */
  override def currentPersistenceIds(): Source[String, NotUsed] = {
    reprSourceToPersistenceIds(newCurrentMessageSource)
  }

  private def reprSourceToPersistenceIds(src: Source[PersistentRepr, NotUsed]): Source[String, NotUsed] = {
    src
      .scan( (Set.empty[String], None:Option[String]) ) {
        case ((known, _), repr) if known.contains(repr.persistenceId) =>
          (known, None)
        case ((known, _), repr) /* if !known.contains(repr.persistenceId) */ =>
          (known, Some(repr.persistenceId))
      }
      .collect { case (_, Some(newPersistenceId)) =>
        newPersistenceId
      }
  }

  /**
    * Query events for a specific `PersistentActor` identified by `persistenceId`.
    *
    * You can retrieve a subset of all events by specifying `fromSequenceNr` and `toSequenceNr`
    * or use `0L` and `Long.MaxValue` respectively to retrieve all events.
    *
    * The returned event stream should be ordered by sequence number.
    *
    * The stream is not completed when it reaches the end of the currently stored events,
    * but it continues to push new events when new events are persisted.
    * Corresponding query that is completed when it reaches the end of the currently
    * stored events is provided by [[CurrentEventsByPersistenceIdQuery#currentEventsByPersistenceId]].
    */
  override def eventsByPersistenceId(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long): Source[EventEnvelope, NotUsed] = {
    reprSourceByPersistenceId(newLiveMessageSource)(persistenceId, fromSequenceNr, toSequenceNr)
  }

  /**
    * Same type of query as [[EventsByPersistenceIdQuery#eventsByPersistenceId]]
    * but the event stream is completed immediately when it reaches the end of
    * the "result set". Events that are stored after the query is completed are
    * not included in the event stream.
    */
  override def currentEventsByPersistenceId(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long): Source[EventEnvelope, NotUsed] = {
    reprSourceByPersistenceId(newCurrentMessageSource)(persistenceId, fromSequenceNr, toSequenceNr)
  }

  private def reprSourceByPersistenceId(src: Source[PersistentRepr, NotUsed])(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long): Source[EventEnvelope, NotUsed] = {
    if (fromSequenceNr > toSequenceNr) {
      Source.empty
    } else {
      src
        .filter(_.persistenceId == persistenceId)
        .filter(_.sequenceNr >= fromSequenceNr)
        .takeWhile(_.sequenceNr <= toSequenceNr)
        .takeWhile(_.sequenceNr < toSequenceNr, inclusive = true) /* we stop as soon as _.sequenceNr == toSequenceNr and we want to return that one, too */
        .map(repr => (repr, repr.payload))
        .map {
          case (repr, Tagged(realPayload, tags)) =>
            (repr, realPayload) // strip tags
          case (repr, realPayload) =>
            (repr, realPayload) // it's actually okay
        }
        .zipWithIndex // starts counting at 0L, offsets start at 1L
        .map { case ((repr, event), offset) =>
          EventEnvelope(Sequence(offset + 1L), repr.persistenceId, repr.sequenceNr, event)
        }
        .withAttributes(streamLogLevels)
    }
  }

  /**
    * Query events that have a specific tag. A tag can for example correspond to an
    * aggregate root type (in DDD terminology).
    *
    * The consumer can keep track of its current position in the event stream by storing the
    * `offset` and restart the query from a given `offset` after a crash/restart.
    *
    * The exact meaning of the `offset` depends on the journal and must be documented by the
    * read journal plugin. It may be a sequential id number that uniquely identifies the
    * position of each event within the event stream. Distributed data stores cannot easily
    * support those semantics and they may use a weaker meaning. For example it may be a
    * timestamp (taken when the event was created or stored). Timestamps are not unique and
    * not strictly ordered, since clocks on different machines may not be synchronized.
    *
    * The returned event stream should be ordered by `offset` if possible, but this can also be
    * difficult to fulfill for a distributed data store. The order must be documented by the
    * read journal plugin.
    *
    * The stream is not completed when it reaches the end of the currently stored events,
    * but it continues to push new events when new events are persisted.
    * Corresponding query that is completed when it reaches the end of the currently
    * stored events is provided by [[CurrentEventsByTagQuery#currentEventsByTag]].
    */
  override def eventsByTag(tag: String, offset: Offset): Source[EventEnvelope, NotUsed] = {
    reprSourceByTag(newLiveMessageSource, tag, offset)
  }

  /**
    * Same type of query as [[EventsByTagQuery#eventsByTag]] but the event stream
    * is completed immediately when it reaches the end of the "result set". Events that are
    * stored after the query is completed are not included in the event stream.
    */
  override def currentEventsByTag(tag: String, offset: Offset): Source[EventEnvelope, NotUsed] = {
    reprSourceByTag(newCurrentMessageSource, tag, offset)
  }

  private def reprSourceByTag(src: Source[PersistentRepr, NotUsed], tag: String, offset: Offset): Source[EventEnvelope, NotUsed] = {
    def truePredicate(x: (PersistentRepr, Any), eventOffset: Long): Boolean = true
    def acceptAfterMinOffset(minOffset: Long)(x: (PersistentRepr, Any), eventOffset: Long): Boolean = eventOffset > minOffset

    val filterPredicate = offset match {
      case NoOffset => truePredicate _
      case Sequence(minOffset) => acceptAfterMinOffset(minOffset) _
      case _ => throw new IllegalArgumentException(s"Unsupported offset ${offset} must be a Sequence or NoOffset")
    }

    src
      .map(repr => (repr, repr.payload))
      .collect {
        case (repr, Tagged(realPayload, tags)) if tags.contains(tag) =>
          (repr, realPayload) // strip tags
        /* we drop untagged events or tagged events that don't contain our tag*/
      }
      .zipWithIndex // counts at 0L, offsets count at 1L
      .filter { case (x, eventOffset) => filterPredicate(x, eventOffset + 1L) }
      .map { case ((repr, event), eventOffset) =>
        EventEnvelope(Sequence(eventOffset + 1L), repr.persistenceId, repr.sequenceNr, event)
      }

  }


}

object InmemReadJournal {
  /**
    * The default identifier for [[InmemReadJournal]] to be used with
    * [[akka.persistence.query.PersistenceQuery#readJournalFor]].
    *
    * The value is `"akka.persistence.query.journal.inmem"` and corresponds
    * to the absolute path to the read journal configuration entry.
    */
  final val Identifier = "akka.persistence.query.journal.inmem"

}