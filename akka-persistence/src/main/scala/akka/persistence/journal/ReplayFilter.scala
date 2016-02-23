/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.persistence.journal

import akka.actor.ActorRef
import akka.actor.Actor
import akka.persistence.JournalProtocol
import java.util.LinkedList
import akka.actor.Props
import akka.actor.ActorLogging
import scala.collection.mutable.LinkedHashSet

/**
 * INTERNAL API
 *
 * Detect corrupt event stream during replay. It uses the the writerUuid and the
 * sequenceNr in the replayed events to find events emitted by overlapping writers.
 */
private[akka] object ReplayFilter {
  def props(
    persistentActor: ActorRef,
    mode: Mode,
    windowSize: Int,
    maxOldWriters: Int,
    debugEnabled: Boolean): Props = {
    require(windowSize > 0, "windowSize must be > 0")
    require(maxOldWriters > 0, "maxOldWriters must be > 0")
    require(mode != Disabled, "mode must not be Disabled")
    Props(new ReplayFilter(persistentActor, mode, windowSize, maxOldWriters, debugEnabled))
  }

  // for binary compatibility
  def props(
    persistentActor: ActorRef,
    mode: Mode,
    windowSize: Int,
    maxOldWriters: Int): Props = props(persistentActor, mode, windowSize, maxOldWriters, debugEnabled = false)

  sealed trait Mode
  case object Fail extends Mode
  case object Warn extends Mode
  case object RepairByDiscardOld extends Mode
  case object Disabled extends Mode
}

/**
 * INTERNAL API
 */
private[akka] class ReplayFilter(persistentActor: ActorRef, mode: ReplayFilter.Mode,
                                 windowSize: Int, maxOldWriters: Int, debugEnabled: Boolean)
  extends Actor with ActorLogging {
  import JournalProtocol._
  import ReplayFilter.{ Warn, Fail, RepairByDiscardOld, Disabled }

  // for binary compatibility
  def this(persistentActor: ActorRef, mode: ReplayFilter.Mode,
           windowSize: Int, maxOldWriters: Int) = this(persistentActor, mode, windowSize, maxOldWriters, debugEnabled = false)

  val buffer = new LinkedList[ReplayedMessage]()
  val oldWriters = LinkedHashSet.empty[String]
  var writerUuid = ""
  var seqNo = -1L

  def receive = {
    case r @ ReplayedMessage(persistent) ⇒
      if (debugEnabled)
        log.debug("Replay: {}", persistent)
      try {
        if (buffer.size == windowSize) {
          val msg = buffer.removeFirst()
          persistentActor.tell(msg, Actor.noSender)
        }

        if (r.persistent.writerUuid == writerUuid) {
          // from same writer
          if (r.persistent.sequenceNr < seqNo) {
            val errMsg = s"Invalid replayed event [${r.persistent.sequenceNr}] in wrong order from " +
              s"writer [${r.persistent.writerUuid}] with persistenceId [${r.persistent.persistenceId}]"
            logIssue(errMsg)
            mode match {
              case RepairByDiscardOld ⇒ // discard
              case Fail               ⇒ throw new IllegalStateException(errMsg)
              case Warn               ⇒ buffer.add(r)
              case Disabled           ⇒ throw new IllegalArgumentException("mode must not be Disabled")
            }
          } else {
            // note that it is alright with == seqNo, since such may be emitted EventSeq
            buffer.add(r)
            seqNo = r.persistent.sequenceNr
          }

        } else if (oldWriters.contains(r.persistent.writerUuid)) {
          // from old writer
          val errMsg = s"Invalid replayed event [${r.persistent.sequenceNr}] from old " +
            s"writer [${r.persistent.writerUuid}] with persistenceId [${r.persistent.persistenceId}]"
          logIssue(errMsg)
          mode match {
            case RepairByDiscardOld ⇒ // discard
            case Fail               ⇒ throw new IllegalStateException(errMsg)
            case Warn               ⇒ buffer.add(r)
            case Disabled           ⇒ throw new IllegalArgumentException("mode must not be Disabled")
          }

        } else {
          // from new writer
          if (writerUuid != "")
            oldWriters.add(writerUuid)
          if (oldWriters.size > maxOldWriters)
            oldWriters.remove(oldWriters.head)

          writerUuid = r.persistent.writerUuid
          seqNo = r.persistent.sequenceNr

          // clear the buffer from messages from other writers with higher seqNo
          val iter = buffer.iterator()
          while (iter.hasNext()) {
            val msg = iter.next()
            if (msg.persistent.sequenceNr >= seqNo) {
              val errMsg = s"Invalid replayed event [${msg.persistent.sequenceNr}] in buffer from old " +
                s"writer [${msg.persistent.writerUuid}] with persistenceId [${msg.persistent.persistenceId}]"
              logIssue(errMsg)
              mode match {
                case RepairByDiscardOld ⇒ iter.remove() // discard
                case Fail               ⇒ throw new IllegalStateException(errMsg)
                case Warn               ⇒ // keep
                case Disabled           ⇒ throw new IllegalArgumentException("mode must not be Disabled")
              }

            }
          }

          buffer.add(r)
        }

      } catch {
        case e: IllegalStateException if mode == Fail ⇒ fail(e)
      }

    case msg @ (_: RecoverySuccess | _: ReplayMessagesFailure) ⇒
      if (debugEnabled)
        log.debug("Replay completed: {}", msg)
      sendBuffered()
      persistentActor.tell(msg, Actor.noSender)
      context.stop(self)
  }

  def sendBuffered(): Unit = {
    val iter = buffer.iterator()
    while (iter.hasNext())
      persistentActor.tell(iter.next(), Actor.noSender)
    buffer.clear()
  }

  def logIssue(errMsg: String): Unit = mode match {
    case Warn | RepairByDiscardOld ⇒ log.warning(errMsg)
    case Fail                      ⇒ log.error(errMsg)
    case Disabled                  ⇒ throw new IllegalArgumentException("mode must not be Disabled")
  }

  def fail(cause: IllegalStateException): Unit = {
    buffer.clear()
    persistentActor.tell(ReplayMessagesFailure(cause), Actor.noSender)
    context.become {
      case _: ReplayedMessage ⇒ // discard
      case msg @ (_: RecoverySuccess | _: ReplayMessagesFailure) ⇒
        context.stop(self)
    }
  }

}
