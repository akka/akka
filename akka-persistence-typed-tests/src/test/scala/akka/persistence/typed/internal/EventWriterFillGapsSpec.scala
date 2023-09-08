/*
 * Copyright (C) 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.pattern.StatusReply
import akka.persistence.AtomicWrite
import akka.persistence.JournalProtocol

object EventWriterFillGapsSpec {
  def config =
    ConfigFactory.parseString(s"""
      akka.persistence.journal.inmem.delay-writes=10ms
    """).withFallback(ConfigFactory.load()).resolve()
}

class EventWriterFillGapsSpec
    extends ScalaTestWithActorTestKit(EventWriterFillGapsSpec.config)
    with AnyWordSpecLike
    with LogCapturing {

  val settings = EventWriter.EventWriterSettings(10, 5.seconds, fillSequenceNumberGaps = true)
  implicit val ec: ExecutionContext = testKit.system.executionContext

  "The event writer" should {

    "handle events without gaps when starting at 1" in new TestSetup {
      sendWrite(1)
      journalAckWrite()
      clientExpectSuccess(1)

      sendWrite(2)
      journalAckWrite()
      clientExpectSuccess(1)

      sendWrite(3)
      journalAckWrite()
      clientExpectSuccess(1)
    }

    "handle events without gaps when starting at > 1" in new TestSetup {
      sendWrite(3)
      journalHighestSeqNr(2)
      journalAckWrite()
      clientExpectSuccess(1)

      sendWrite(4)
      journalAckWrite()
      clientExpectSuccess(1)
    }

    "handle duplicates" in new TestSetup {
      sendWrite(1)
      journalAckWrite()
      clientExpectSuccess(1)

      // should also be ack:ed
      sendWrite(1)
      // duplicates detected before journal write
      fakeJournal.expectNoMessage()
      clientExpectSuccess(1)
    }

    "handle batched duplicates" in new TestSetup {
      // first write, and wait for ack so that this test doesn't have to lookup latest seqNr
      sendWrite(1)
      journalAckWrite()
      clientExpectSuccess(1)

      sendWrite(2)

      // first batch
      for (n <- 3L to 10L) {
        sendWrite(n)
      }
      // 2 will be written directly
      journalAckWrite() should ===(1)
      clientExpectSuccess(1)

      // completing 1 triggers write of batch with 3-10
      // second
      for (n <- 1L to 10L) {
        sendWrite(n)
      }
      // batch 3-10 in flight, writes in the meanwhile go in a new batch
      journalAckWrite() should ===(8)
      // duplicates detected before journal write

      clientExpectSuccess(18)
    }

    "handle batches with half duplicates" in new TestSetup {
      // first write, and wait for ack so that this test doesn't have to lookup latest seqNr
      sendWrite(1)
      journalAckWrite()
      clientExpectSuccess(1)

      sendWrite(1)
      for (n <- 2L to 10L) {
        sendWrite(n)
      }
      journalAckWrite() should ===(1)
      journalAckWrite() should ===(8)
      clientExpectSuccess(9)

      for (n <- 5L until 15L) {
        sendWrite(n)
      }
      // duplicates detected before journal write
      journalAckWrite() should ===(1) // new write of 11 (non duplicates)
      journalAckWrite() should ===(3) // new write of 12-15 (non duplicates)

      // all writes succeeded
      clientExpectSuccess(10)
    }

    "pass real errors from journal back" in new TestSetup {
      sendWrite(1L)
      journalFailWrite("error error")
      // duplicate handling will ask for highest seq nr, can't know it is an actual error
      journalHighestSeqNr(0L)
      val response = clientProbe.receiveMessage()
      response.isError should ===(true)
      response.getError.getMessage should ===("Journal write failed")
    }

    "handle writes to many pids" in {
      val writer = spawn(EventWriter("akka.persistence.journal.inmem", settings))
      val probe = createTestProbe[StatusReply[EventWriter.WriteAck]]()
      (1 to 1000).map { pidN =>
        Future {
          for (n <- 1 to 20) {
            writer ! EventWriter.Write(s"pid$pidN", n.toLong, n.toString, None, Set.empty, probe.ref)
          }
        }
      }
      // FIXME something is not working as expected with this test, it's detecting gaps and filling them
      // see log message "adding sequence nrs"
      probe.receiveMessages(20 * 1000, 20.seconds)
    }

    // FIXME more tests of the actual gap fill
  }

  trait TestSetup {
    def pid1 = "pid1"
    val fakeJournal = createTestProbe[JournalProtocol.Message]()
    val writer = spawn(EventWriter(fakeJournal.ref, settings))
    val clientProbe = createTestProbe[StatusReply[EventWriter.WriteAck]]()
    def sendWrite(seqNr: Long, pid: String = pid1): Unit = {
      writer ! EventWriter.Write(pid, seqNr, seqNr.toString, None, Set.empty, clientProbe.ref)
    }
    def journalAckWrite(pid: String = pid1): Int = {
      val write = fakeJournal.expectMessageType[JournalProtocol.WriteMessages]
      write.messages should have size (1)
      val atomicWrite = write.messages.head.asInstanceOf[AtomicWrite]
      atomicWrite.payload.foreach { repr =>
        repr.persistenceId should ===(pid)
        write.persistentActor ! JournalProtocol.WriteMessageSuccess(repr, write.actorInstanceId)
      }
      write.persistentActor ! JournalProtocol.WriteMessagesSuccessful
      atomicWrite.payload.size
    }

    def journalFailWrite(reason: String, pid: String = pid1): Int = {
      val write = fakeJournal.expectMessageType[JournalProtocol.WriteMessages]
      write.messages should have size (1)
      val atomicWrite = write.messages.head.asInstanceOf[AtomicWrite]
      atomicWrite.payload.foreach { repr =>
        repr.persistenceId should ===(pid)
        write.persistentActor ! JournalProtocol.WriteMessageFailure(
          repr,
          new RuntimeException(reason),
          write.actorInstanceId)
      }
      write.persistentActor ! JournalProtocol.WriteMessagesFailed
      atomicWrite.payload.size
    }

    def journalHighestSeqNr(highestSeqNr: Long): Unit = {
      val replay = fakeJournal.expectMessageType[JournalProtocol.ReplayMessages]
      replay.persistentActor ! JournalProtocol.RecoverySuccess(highestSeqNr)
    }

    def clientExpectSuccess(n: Int) = {
      clientProbe.receiveMessages(n).foreach { reply =>
        reply.isSuccess should be(true)
      }
    }
  }

}
