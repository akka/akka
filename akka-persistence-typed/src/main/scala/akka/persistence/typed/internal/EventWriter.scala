/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Success

import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.Extension
import akka.actor.typed.ExtensionId
import akka.actor.typed.SupervisorStrategy
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.LoggerOps
import akka.actor.typed.scaladsl.adapter.ClassicActorRefOps
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import akka.annotation.InternalStableApi
import akka.pattern.StatusReply
import akka.persistence.AtomicWrite
import akka.persistence.FilteredPayload
import akka.persistence.JournalProtocol
import akka.persistence.Persistence
import akka.persistence.PersistentRepr
import akka.persistence.journal.Tagged
import akka.util.JavaDurationConverters.JavaDurationOps
import akka.util.Timeout

/**
 * INTERNAL API
 */
@InternalStableApi
private[akka] object EventWriterExtension extends ExtensionId[EventWriterExtension] {
  def createExtension(system: ActorSystem[_]): EventWriterExtension = new EventWriterExtension(system)

  def get(system: ActorSystem[_]): EventWriterExtension = apply(system)
}

/**
 * INTERNAL API
 */
@InternalStableApi
private[akka] class EventWriterExtension(system: ActorSystem[_]) extends Extension {

  private val settings = EventWriter.EventWriterSettings(system)
  private val writersPerJournalId = new ConcurrentHashMap[String, ActorRef[EventWriter.Command]]()

  def writerForJournal(journalId: Option[String]): ActorRef[EventWriter.Command] =
    writersPerJournalId.computeIfAbsent(
      journalId.getOrElse(""), { _ =>
        system.systemActorOf(
          EventWriter(journalId.getOrElse(""), settings),
          s"EventWriter-${URLEncoder.encode(journalId.getOrElse("default"), "UTF-8")}")
      })

}

/**
 * INTERNAL API
 */
@InternalStableApi
private[akka] object EventWriter {

  type SeqNr = Long
  type Pid = String

  object EventWriterSettings {
    def apply(system: ActorSystem[_]): EventWriterSettings = {
      val config = system.settings.config.getConfig("akka.persistence.typed.event-writer")
      EventWriterSettings(
        maxBatchSize = config.getInt("max-batch-size"),
        askTimeout = config.getDuration("ask-timeout").asScala,
        fillSequenceNumberGaps = config.getBoolean("fill-sequence-number-gaps"),
        latestSequenceNumberCacheCapacity = config.getInt("latest-sequence-number-cache-capacity"))
    }

  }
  final case class EventWriterSettings(
      maxBatchSize: Int,
      askTimeout: FiniteDuration,
      fillSequenceNumberGaps: Boolean,
      latestSequenceNumberCacheCapacity: Int)

  sealed trait Command
  final case class Write(
      persistenceId: Pid,
      sequenceNumber: SeqNr,
      event: Any,
      metadata: Option[Any],
      tags: Set[String],
      replyTo: ActorRef[StatusReply[WriteAck]])
      extends Command
  final case class WriteAck(persistenceId: Pid, sequenceNumber: SeqNr)

  private final case class MaxSeqNrForPid(persistenceId: Pid, sequenceNumber: SeqNr, originalErrorDesc: Option[String])
      extends Command

  private case class StateForPid(
      waitingForReply: Map[SeqNr, (PersistentRepr, ActorRef[StatusReply[WriteAck]])],
      waitingForWrite: Vector[(PersistentRepr, ActorRef[StatusReply[WriteAck]])] = Vector.empty,
      writeErrorHandlingInProgress: Boolean = false,
      currentTransactionId: Int = 0,
      latestSeqNr: SeqNr = -1L,
      usedTimestamp: Long = 0L,
      waitingForSeqNrLookup: Vector[(PersistentRepr, ActorRef[StatusReply[WriteAck]])] = Vector.empty) {
    def idle: Boolean =
      waitingForReply.isEmpty && waitingForWrite.isEmpty

    def seqNrlookupInProgress: Boolean =
      waitingForSeqNrLookup.nonEmpty

    def nextExpectedSeqNr: SeqNr =
      if (waitingForWrite.nonEmpty)
        waitingForWrite.last._1.sequenceNr + 1
      else if (waitingForReply.nonEmpty)
        waitingForReply.keysIterator.max + 1
      else if (latestSeqNr == -1L)
        1L
      else
        latestSeqNr + 1

    def waitingForWriteExceedingMaxBatchSize(batchSize: Int): Boolean =
      waitingForWrite.size >= batchSize &&
      waitingForWrite.count { case (repr, _) => repr.payload != FilteredPayload } >= batchSize
  }

  def apply(journalPluginId: String, settings: EventWriterSettings): Behavior[Command] =
    Behaviors
      .supervise(Behaviors.setup[Command] { context =>
        val journal = Persistence(context.system).journalFor(journalPluginId)
        context.log.debug("Event writer for journal [{}] starting up", journalPluginId)
        apply(journal.toTyped, settings)
      })
      .onFailure[Exception](SupervisorStrategy.restart)

  def apply(journal: ActorRef[JournalProtocol.Message], settings: EventWriterSettings): Behavior[Command] =
    Behaviors
      .setup[AnyRef] { context =>
        val writerUuid = UUID.randomUUID().toString

        var perPidWriteState = Map.empty[Pid, StateForPid]
        implicit val askTimeout: Timeout = settings.askTimeout
        import settings.fillSequenceNumberGaps

        def sendToJournal(transactionId: Int, reprs: Vector[PersistentRepr]): Unit = {
          if (context.log.isTraceEnabled)
            context.log.traceN(
              "Writing events persistence id [{}], sequence nrs [{}-{}]",
              reprs.head.persistenceId,
              reprs.head.sequenceNr,
              reprs.last.sequenceNr)
          journal ! JournalProtocol.WriteMessages(
            AtomicWrite(reprs) :: Nil,
            context.self.toClassic,
            // Note: we use actorInstanceId to correlate replies from one write request
            actorInstanceId = transactionId)
        }

        def handleUpdatedStateForPid(pid: Pid, newStateForPid: StateForPid): Unit = {
          if (newStateForPid.waitingForReply.nonEmpty) {
            // more waiting replyTo before we could batch it or scrap the entry
            perPidWriteState = perPidWriteState.updated(pid, newStateForPid)
          } else {
            if (newStateForPid.waitingForWrite.isEmpty && fillSequenceNumberGaps) {
              perPidWriteState = perPidWriteState.updated(pid, newStateForPid)
              evictLeastRecentlyUsedPids()
            } else if (newStateForPid.waitingForWrite.isEmpty) {
              perPidWriteState = perPidWriteState - pid
            } else {
              // batch waiting for pid
              val newReplyTo = newStateForPid.waitingForWrite.map {
                case (repr, replyTo) => (repr.sequenceNr, (repr, replyTo))
              }.toMap
              val updatedState =
                newStateForPid.copy(
                  newReplyTo,
                  waitingForWrite = Vector.empty,
                  currentTransactionId = newStateForPid.currentTransactionId + 1)

              if (context.log.isTraceEnabled())
                context.log.traceN(
                  "Writing batch of {} events for pid [{}], seq nrs [{}-{}], tx id [{}]",
                  newStateForPid.waitingForWrite.size,
                  pid,
                  newStateForPid.waitingForWrite.head._1.sequenceNr,
                  newStateForPid.waitingForWrite.last._1.sequenceNr,
                  updatedState.currentTransactionId)

              val batch = newStateForPid.waitingForWrite.map { case (repr, _) => repr }
              sendToJournal(updatedState.currentTransactionId, batch)
              perPidWriteState = perPidWriteState.updated(pid, updatedState)

            }
          }
        }

        def askMaxSeqNr(pid: Pid, originalErrorDesc: Option[String]): Unit = {
          context.log.trace("Request highest sequence number for pid [{}]", pid)
          context.ask(
            journal,
            (replyTo: ActorRef[JournalProtocol.Response]) =>
              JournalProtocol.ReplayMessages(0L, 0L, 1L, pid, replyTo.toClassic)) {
            case Success(JournalProtocol.RecoverySuccess(highestSequenceNr)) =>
              MaxSeqNrForPid(pid, highestSequenceNr, originalErrorDesc)
            case Success(unexpected) =>
              throw new IllegalArgumentException(
                s"Got unexpected reply from journal ${unexpected.getClass} for pid $pid")
            case Failure(exception) =>
              throw new RuntimeException(s"Error finding highest sequence number in journal for pid $pid", exception)
          }
        }

        def evictLeastRecentlyUsedPids(): Unit = {
          import settings.latestSequenceNumberCacheCapacity
          val accumulationFactor = 1.1
          if (perPidWriteState.size >= latestSequenceNumberCacheCapacity * accumulationFactor) {
            val idleEntries =
              perPidWriteState.iterator.filter {
                case (_, stateForPid) => stateForPid.idle && stateForPid.waitingForSeqNrLookup.isEmpty
              }.toVector

            if (idleEntries.size >= latestSequenceNumberCacheCapacity * accumulationFactor) {
              val pidsToRemove =
                idleEntries
                  .sortBy {
                    case (_, stateForPid) => stateForPid.usedTimestamp
                  }
                  .take(idleEntries.size - latestSequenceNumberCacheCapacity)
                  .map { case (pid, _) => pid }

              if (context.log.isTraceEnabled)
                context.log.traceN(
                  "Evicted cache from [{}] to [{}], persistence ids [{}]",
                  idleEntries.size,
                  idleEntries.size - pidsToRemove.size,
                  pidsToRemove.mkString(", "))

              perPidWriteState --= pidsToRemove
            }
          }
        }

        def handleJournalResponse(response: JournalProtocol.Response): Behavior[AnyRef] =
          response match {
            case JournalProtocol.WriteMessageSuccess(message, transactionId) =>
              val pid = message.persistenceId
              val sequenceNr = message.sequenceNr
              perPidWriteState.get(pid) match {
                case None =>
                  context.log.debugN(
                    "Got write success reply for event with no previous state for pid, ignoring (pid [{}], seq nr [{}], tx id [{}])",
                    pid,
                    sequenceNr,
                    transactionId)
                case Some(stateForPid) =>
                  if (transactionId == stateForPid.currentTransactionId) {
                    stateForPid.waitingForReply.get(sequenceNr) match {
                      case None =>
                        context.log.debugN(
                          "Got write reply for event with no waiting request for seq nr, ignoring (pid [{}], seq nr [{}], tx id [{}])",
                          pid,
                          sequenceNr,
                          transactionId)
                      case Some((_, waiting)) =>
                        context.log.trace2(
                          "Successfully wrote event persistence id [{}], sequence nr [{}]",
                          pid,
                          sequenceNr)
                        waiting ! StatusReply.success(WriteAck(pid, sequenceNr))
                        val newState = stateForPid.copy(
                          waitingForReply = stateForPid.waitingForReply - sequenceNr,
                          latestSeqNr = sequenceNr,
                          usedTimestamp = System.currentTimeMillis())
                        handleUpdatedStateForPid(pid, newState)
                    }
                  } else {
                    if (context.log.isTraceEnabled) {
                      context.log.traceN(
                        "Got reply for old tx id [{}] (current [{}]) for pid [{}], ignoring",
                        transactionId,
                        stateForPid.currentTransactionId,
                        pid)
                    }
                  }
              }
              Behaviors.same

            case JournalProtocol.WriteMessageFailure(message, error, transactionId) =>
              val pid = message.persistenceId
              val sequenceNr = message.sequenceNr
              perPidWriteState.get(pid) match {
                case None =>
                  context.log.debugN(
                    "Got write error reply for event with no previous state for pid, ignoring (pid [{}], seq nr [{}], tx id [{}])",
                    pid,
                    sequenceNr,
                    transactionId)
                case Some(state) =>
                  // write failure could be re-delivery, we need to check
                  state.waitingForReply.get(sequenceNr) match {
                    case None =>
                      context.log.debugN(
                        "Got write error reply for event with no waiting request for seq nr, ignoring (pid [{}], seq nr [{}], tx id [{}])",
                        pid,
                        sequenceNr,
                        transactionId)
                    case Some(_) =>
                      // quite likely a re-delivery of already persisted events, the whole batch will fail
                      // check highest seqnr and see if we can ack events
                      if (!state.writeErrorHandlingInProgress && state.currentTransactionId == transactionId) {
                        perPidWriteState =
                          perPidWriteState.updated(pid, state.copy(writeErrorHandlingInProgress = true))
                        askMaxSeqNr(pid, Some(error.getMessage))
                      } else {
                        context.log.traceN(
                          "Ignoring failure for pid [{}], seq nr [{}], tx id [{}], since write error handling already in progress or old tx id (current tx id [{}])",
                          pid,
                          sequenceNr,
                          transactionId,
                          state.currentTransactionId)
                      }
                  }
              }
              Behaviors.same

            case _ =>
              // ignore all other journal protocol messages
              Behaviors.same
          }

        def handleWrite(repr: PersistentRepr, replyTo: ActorRef[StatusReply[WriteAck]], calledAfterMaxSeqNr: Boolean)
            : Unit = {
          val persistenceId = repr.persistenceId
          val sequenceNumber = repr.sequenceNr
          val newStateForPid =
            perPidWriteState.get(persistenceId) match {
              case None =>
                if (fillSequenceNumberGaps && sequenceNumber != 1L) {
                  askMaxSeqNr(persistenceId, originalErrorDesc = None)
                  StateForPid(waitingForReply = Map.empty, waitingForSeqNrLookup = Vector((repr, replyTo)))
                } else {
                  sendToJournal(1, Vector(repr))
                  StateForPid(Map((repr.sequenceNr, (repr, replyTo))), currentTransactionId = 1)
                }

              case Some(state) =>
                val expectedSeqNr =
                  if (fillSequenceNumberGaps) state.nextExpectedSeqNr
                  else repr.sequenceNr

                if (state.seqNrlookupInProgress) {
                  context.log.trace2(
                    "Seq nr lookup in progress for persistence id [{}], adding sequence nr [{}] to pending",
                    persistenceId,
                    sequenceNumber)
                  state.copy(waitingForSeqNrLookup = state.waitingForSeqNrLookup :+ ((repr, replyTo)))
                } else if (sequenceNumber == expectedSeqNr) {
                  if (state.idle) {
                    sendToJournal(state.currentTransactionId + 1, Vector(repr))
                    state.copy(
                      waitingForReply = Map((repr.sequenceNr, (repr, replyTo))),
                      currentTransactionId = state.currentTransactionId + 1)
                  } else {
                    // write in progress for pid, add write to batch and perform once current write completes
                    if (state.waitingForWriteExceedingMaxBatchSize(settings.maxBatchSize)) {
                      replyTo ! StatusReply.error(
                        s"Max batch reached for pid $persistenceId, at most ${settings.maxBatchSize} writes for " +
                        "the same pid may be in flight at the same time")
                      state
                    } else {
                      context.log.trace2(
                        "Writing event in progress for persistence id [{}], adding sequence nr [{}] to batch",
                        persistenceId,
                        sequenceNumber)
                      state.copy(waitingForWrite = state.waitingForWrite :+ ((repr, replyTo)))
                    }
                  }
                } else if (sequenceNumber < expectedSeqNr) {
                  context.log.trace2("Duplicate seq nr [{}] for persistence id [{}]", sequenceNumber, persistenceId)
                  replyTo ! StatusReply.success(WriteAck(persistenceId, sequenceNumber)) // duplicate
                  state
                } else { // sequenceNumber > expectedSeqNr
                  require(
                    fillSequenceNumberGaps,
                    s"Unexpected sequence number gap, expected [$expectedSeqNr], received [$sequenceNumber]. " +
                    "Enable akka.persistence.typed.event-writer.fill-sequence-number-gaps config if gaps are " +
                    "expected and should be filled with FilteredPayload.")

                  if (calledAfterMaxSeqNr || !state.idle) {
                    // here we can trust that the expectedSeqNr is correct and don't have to lookup max
                    val fillRepr = (expectedSeqNr until sequenceNumber).map { n =>
                      PersistentRepr(
                        FilteredPayload,
                        persistenceId = persistenceId,
                        sequenceNr = n,
                        manifest = "", // adapters would be on the producing side, already applied
                        writerUuid = writerUuid,
                        sender = akka.actor.ActorRef.noSender)
                    }.toVector
                    context.log.debugN(
                      "Detected sequence nr gap [{}-{}] for persistence id [{}]. Filling with FilteredPayload.",
                      fillRepr.head.sequenceNr,
                      fillRepr.last.sequenceNr,
                      persistenceId)
                    val ignoreRef = context.system.ignoreRef
                    if (state.idle) {
                      sendToJournal(state.currentTransactionId + 1, fillRepr :+ repr)
                      val newWaitingForReply =
                        (fillRepr.map(r => r.sequenceNr -> (r -> ignoreRef)) :+ (repr.sequenceNr -> (repr -> replyTo))).toMap
                      state.copy(
                        waitingForReply = newWaitingForReply,
                        currentTransactionId = state.currentTransactionId + 1)
                    } else {
                      if (context.log.isTraceEnabled)
                        context.log.traceN(
                          "Writing event in progress for persistence id [{}], adding sequence nrs [{}-{}] to batch",
                          persistenceId,
                          fillRepr.head.sequenceNr,
                          sequenceNumber)
                      val newWaitingForWrite = fillRepr.map(_ -> ignoreRef) :+ (repr -> replyTo)
                      state.copy(waitingForWrite = state.waitingForWrite ++ newWaitingForWrite)
                    }
                  } else {
                    // No pending writes or we haven't just looked up latest sequence nr.
                    // The reason we don't trust a previous state.latestSeqNr is because another EventWriter
                    // on another node might have been taking writes since we retrieved it.
                    askMaxSeqNr(persistenceId, originalErrorDesc = None)
                    context.log.trace2(
                      "Seq nr lookup needed for persistence id [{}], adding sequence nr [{}] to pending",
                      persistenceId,
                      sequenceNumber)
                    state.copy(waitingForSeqNrLookup = state.waitingForSeqNrLookup :+ ((repr, replyTo)))
                  }

                }
            }
          perPidWriteState = perPidWriteState.updated(persistenceId, newStateForPid)
        }

        Behaviors.receiveMessage {
          case Write(persistenceId, sequenceNumber, event, metadata, tags, replyTo) =>
            val payload = if (tags.isEmpty) event else Tagged(event, tags)
            val repr = PersistentRepr(
              payload,
              persistenceId = persistenceId,
              sequenceNr = sequenceNumber,
              manifest = "", // adapters would be on the producing side, already applied
              writerUuid = writerUuid,
              sender = akka.actor.ActorRef.noSender)

            val reprWithMeta = metadata match {
              case Some(meta) => repr.withMetadata(meta)
              case _          => repr
            }

            handleWrite(reprWithMeta, replyTo, calledAfterMaxSeqNr = false)
            Behaviors.same

          case MaxSeqNrForPid(pid, maxSeqNr, None) =>
            perPidWriteState.get(pid) match {
              case None =>
                context.log.debug("Got max seq nr with no waiting previous state for pid, ignoring (pid [{}])", pid)
              case Some(state) =>
                context.log.debug("Retrieved max seq nr [{}] for pid [{}]", maxSeqNr, pid)
                val waiting = state.waitingForSeqNrLookup
                perPidWriteState = perPidWriteState.updated(
                  pid,
                  state.copy(latestSeqNr = maxSeqNr, waitingForSeqNrLookup = Vector.empty))
                waiting.foreach { case (repr, replyTo) => handleWrite(repr, replyTo, calledAfterMaxSeqNr = true) }
            }
            Behaviors.same

          case MaxSeqNrForPid(pid, maxSeqNr, Some(errorDesc)) =>
            // write failed, so we looked up the maxSeqNr to detect if it was duplicate events, already in journal
            perPidWriteState.get(pid) match {
              case None =>
                context.log.debug2(
                  "Got max seq nr with no waiting previous state for pid, ignoring (pid [{}], original error desc: {})",
                  pid,
                  errorDesc)
              case Some(state) =>
                val sortedSeqs = state.waitingForReply.keys.toSeq.sorted
                val (alreadyInJournal, needsWrite) = sortedSeqs.partition(seqNr => seqNr <= maxSeqNr)
                if (alreadyInJournal.isEmpty) {
                  // error was not about duplicates
                  state.waitingForReply.values.foreach {
                    case (_, replyTo) =>
                      replyTo ! StatusReply.error("Journal write failed")
                  }
                  context.log.warnN(
                    "Failed writing event batch persistence id [{}], sequence nr [{}-{}]: {}",
                    pid,
                    sortedSeqs.head,
                    sortedSeqs.last,
                    errorDesc)
                  val newState = state.copy(waitingForReply = Map.empty, writeErrorHandlingInProgress = false)
                  handleUpdatedStateForPid(pid, newState)
                } else {
                  // ack all already written
                  val stateAfterWritten = alreadyInJournal
                    .foldLeft(state) { (state, seqNr) =>
                      val (_, replyTo) = state.waitingForReply(seqNr)
                      replyTo ! StatusReply.success(WriteAck(pid, seqNr))
                      state.copy(waitingForReply = state.waitingForReply - seqNr)
                    }
                    .copy(writeErrorHandlingInProgress = false)
                  if (needsWrite.isEmpty) {
                    handleUpdatedStateForPid(pid, stateAfterWritten)
                  } else {
                    // retrigger write for those left if any, note that we do a
                    val reprsToRewrite =
                      stateAfterWritten.waitingForReply.values.map { case (repr, _) => repr }.toVector
                    if (context.log.isDebugEnabled())
                      context.log.debugN(
                        "Partial batch was duplicates, re-triggering write of persistence id [{}], sequence nr [{}-{}]",
                        pid,
                        reprsToRewrite.head.sequenceNr,
                        reprsToRewrite.last.sequenceNr)
                    // Not going via batch/handleUpdatedStateForPid here because adding partial
                    // failure to current waiting could go over batch size limit
                    val partialRewriteState =
                      stateAfterWritten.copy(currentTransactionId = stateAfterWritten.currentTransactionId + 1)
                    sendToJournal(partialRewriteState.currentTransactionId, reprsToRewrite)
                    handleUpdatedStateForPid(pid, partialRewriteState)
                  }

                }

            }
            Behaviors.same

          case response: JournalProtocol.Response => handleJournalResponse(response)

          case unexpected =>
            context.log.warn("Unexpected message sent to EventWriter [{}], ignored", unexpected.getClass)
            Behaviors.same

        }
      }
      .narrow[Command]

}
