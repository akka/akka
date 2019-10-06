/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.scaladsl

import akka.actor.{ ActorSystem, Props }
import akka.persistence._
import akka.persistence.testkit.{ CommonUtils, SnapshotStorage }
import akka.persistence.testkit.{ ExpectedFailure, ProcessingSuccess, StorageFailure }
import akka.persistence.testkit._
import akka.testkit.{ EventFilter, TestKitBase }
import org.scalatest.WordSpecLike
import org.scalatest.Matchers._

trait CommonSnapshotTests extends WordSpecLike with TestKitBase with CommonUtils {

  lazy val testKit = new SnapshotTestKit()(system)

  implicit lazy val sys: ActorSystem = system

  import testKit._

  def specificTests(): Unit

  "SnapshotTestkit" should {

    "save snapshot" in {

      val pid = randomPid()

      val a = system.actorOf(Props(classOf[A], pid, None))

      a ! NewSnapshot(1: Any)
      a ! NewSnapshot(2: Any)

      expectNextPersisted(pid, 1)

      assertThrows[AssertionError] {
        expectNextPersisted(pid, 3)
      }

      expectNextPersisted(pid, 2)

      assertThrows[AssertionError] {
        expectNextPersisted(pid, 3)
      }

    }

    "save snapshot and check with pf" in {

      val pid = randomPid()

      val a = system.actorOf(Props(classOf[A], pid, None))

      a ! NewSnapshot(1: Any)
      a ! NewSnapshot(2: Any)

      expectNextPersistedPF(pid) {
        case 1 =>
      }

      assertThrows[AssertionError] {
        expectNextPersistedPF(pid) {
          case 3 =>
        }
      }

      expectNextPersistedPF(pid) {
        case 2 =>
      }

      assertThrows[AssertionError] {
        expectNextPersistedPF(pid) {
          case 3 =>
        }
      }

    }

    "successfully set and execute custom policy" in {

      val pid = randomPid()

      val err = new Exception("BOOM!")

      val newPolicy = new SnapshotStorage.SnapshotPolicies.PolicyType {
        override def tryProcess(persistenceId: String, processingUnit: SnapshotOperation): ProcessingResult = {
          processingUnit match {
            case WriteSnapshot(_, msgs) =>
              val ex = msgs match {
                case 777 => true
                case _   => false
              }
              if (ex) {
                ProcessingSuccess
              } else {
                StorageFailure(err)
              }
            case _ => ProcessingSuccess
          }
        }
      }

      withPolicy(newPolicy)

      val a = system.actorOf(Props(classOf[A], pid, Some(testActor)))

      a ! NewSnapshot(1)

      expectMsg((List.empty, 0))
      expectMsgPF() { case SaveSnapshotFailure(_, ee) if ee.getMessage == err.getMessage => }

      a ! NewSnapshot(777)

      expectMsgPF() { case SaveSnapshotSuccess(_) => }
      expectNextPersisted(pid, 777)

      returnDefaultPolicy()

    }

    "expect next N valid snapshots in order" in {

      val pid = randomPid()

      val a = system.actorOf(Props(classOf[A], pid, None))

      a ! NewSnapshot(1)
      a ! NewSnapshot(2)

      assertThrows[AssertionError] {
        expectPersistedInOrder(pid, List(2, 1))
      }

      expectPersistedInOrder(pid, List(1, 2))

    }

    "expect next N valid snapshots in any order" in {

      val pid = randomPid()

      val a = system.actorOf(Props(classOf[A], pid, None))

      a ! NewSnapshot(2)
      a ! NewSnapshot(1)

      assertThrows[AssertionError] {
        expectPersistedInAnyOrder(pid, List(3, 2))
      }

      expectPersistedInAnyOrder(pid, List(1, 2))

    }

    "fail next snapshot" in {

      val pid = randomPid()

      val a = system.actorOf(Props(classOf[A], pid, Some(testActor)))

      //consecutive calls should stack
      failNextPersisted()
      failNextPersisted()

      a ! NewSnapshot(1)

      expectMsg((List.empty, 0))
      expectMsgPF() { case SaveSnapshotFailure(_, ExpectedFailure) => }

      val b = system.actorOf(Props(classOf[A], pid, Some(testActor)))

      b ! NewSnapshot(2)

      expectMsg((List.empty, 0))
      expectMsgPF() { case SaveSnapshotFailure(_, ExpectedFailure) => }

      val c = system.actorOf(Props(classOf[A], pid, None))

      c ! NewSnapshot(3)

      expectNextPersisted(pid, 3)

    }

    "fail next snapshot with custom error" in {

      val pid = randomPid()

      val a = system.actorOf(Props(classOf[A], pid, Some(testActor)))

      val err = new Exception("Custom ERROR!")

      failNextPersisted(err)

      a ! NewSnapshot(1)

      expectMsg((List.empty, 0))
      expectMsgPF() { case SaveSnapshotFailure(_, ee) if err.getMessage == ee.getMessage => }

    }

    "expect nothingPersisted fails" in {

      val pid = randomPid()

      val a = system.actorOf(Props(classOf[A], pid, None))

      expectNothingPersisted(pid)

      a ! NewSnapshot(1)

      assertThrows[AssertionError] {
        expectNothingPersisted(pid)
      }

    }

    "expect no snapshot persisted" in {

      val pid = randomPid()

      val a = system.actorOf(Props(classOf[A], pid, None))

      expectNothingPersisted(pid)

      a ! NewSnapshot(1)

      expectNextPersisted(pid, 1)

      expectNothingPersisted(pid)

    }

    "fail recovery" in {

      val pid = randomPid()

      failNextNOps(1)

      val a = system.actorOf(Props(classOf[A], pid, None))

      watch(a)

      expectTerminated(a)

      system.actorOf(Props(classOf[A], pid, Some(testActor)))

      expectMsg((List.empty, 0))

    }

    "recover last persisted snapshot" in {

      val pid = randomPid()
      val preload = List((SnapshotMeta(0), 1), (SnapshotMeta(1), 2), (SnapshotMeta(2), 3))

      persistForRecovery(pid, preload)

      system.actorOf(Props(classOf[A], pid, Some(testActor)))

      expectMsg((List.empty, 3))

    }

    "fail to recover persisted snapshots for any actor" in {

      val pid = randomPid()

      val preload = List((SnapshotMeta(0), 1), (SnapshotMeta(1), 2), (SnapshotMeta(2), 3))

      persistForRecovery(pid, preload)

      failNextRead()

      val a = system.actorOf(Props(classOf[A], pid, Some(testActor)))

      watch(a)
      expectTerminated(a)

    }

    "fail to recover persisted snapshots for any actor with custom error" in {

      val pid = randomPid()

      val preload = List((SnapshotMeta(0), 1), (SnapshotMeta(1), 2), (SnapshotMeta(2), 3))

      val err = new Exception("Custom ERROR!")

      persistForRecovery(pid, preload)

      failNextRead(err)

      EventFilter.error(err.getMessage, occurrences = 1).intercept {
        val a = system.actorOf(Props(classOf[A], pid, Some(testActor)))
        watch(a)
        expectTerminated(a)
      }

    }

    "fail to recover persisted snapshots for actor with particular persistenceId" in {

      val pid = randomPid()
      val preload = List((SnapshotMeta(0), 1), (SnapshotMeta(1), 2), (SnapshotMeta(2), 3))

      persistForRecovery(pid, preload)

      failNextRead(pid)

      val a = system.actorOf(Props(classOf[A], pid, Some(testActor)))

      watch(a)
      expectTerminated(a)

    }

    "recover last persisted snapshot when fail for different persistenceId is set" in {

      val pid = randomPid()

      val preload = List((SnapshotMeta(0), 1), (SnapshotMeta(1), 2), (SnapshotMeta(2), 3))

      persistForRecovery(pid, preload)

      val otherPid = randomPid()

      failNextRead(otherPid)

      system.actorOf(Props(classOf[A], pid, Some(testActor)))

      expectMsg((List.empty, 3))

    }

    "persist and return persisted snapshots" in {

      val pid = randomPid()

      val saved = List((SnapshotMeta(0), 1), (SnapshotMeta(1), 2), (SnapshotMeta(2), 3))

      persistForRecovery(pid, saved)

      val li = persistedInStorage(pid)

      (li should contain).theSameElementsInOrderAs(saved)

    }

    "fail next snapshot delete for any actor" in {

      val pid = randomPid()

      persistForRecovery(pid, List((SnapshotMeta(0), 1)))

      val a = system.actorOf(Props(classOf[A], pid, Some(testActor)))

      failNextDelete()

      a ! DeleteSomeSnapshot(0)

      expectMsg((List.empty, 1))
      expectMsgPF() { case DeleteSnapshotFailure(_, ExpectedFailure) => }

      a ! DeleteSomeSnapshot(0)

      expectMsgPF() { case DeleteSnapshotSuccess(meta) if meta.sequenceNr == 0 => }
      expectNoMessage()

    }

    "fail next snapshot delete for any actor with custom error" in {

      val pid = randomPid()

      persistForRecovery(pid, List((SnapshotMeta(0), 1)))

      val err = new Exception("Custom ERROR!")

      val a = system.actorOf(Props(classOf[A], pid, Some(testActor)))

      failNextDelete(err)

      a ! DeleteSomeSnapshot(0)

      expectMsg((List.empty, 1))
      expectMsgPF() { case DeleteSnapshotFailure(_, ee) if ee.getMessage == err.getMessage => }

      a ! DeleteSomeSnapshot(0)

      expectMsgPF() { case DeleteSnapshotSuccess(meta) if meta.sequenceNr == 0 => }
      expectNoMessage()

    }

    "fail next delete for particular persistence id" in {

      val pid = randomPid()

      persistForRecovery(pid, List((SnapshotMeta(0), 1), (SnapshotMeta(1), 2), (SnapshotMeta(2), 3)))

      val a = system.actorOf(Props(classOf[A], pid, Some(testActor)))

      failNextDelete(pid)

      a ! DeleteSomeSnapshot(0)

      expectMsg((List.empty, 3))
      expectMsgPF() { case DeleteSnapshotFailure(_, ExpectedFailure) => }

      a ! DeleteSomeSnapshot(0)

      expectMsgPF() { case DeleteSnapshotSuccess(meta) if meta.sequenceNr == 0 => }
      expectNoMessage()

    }

    "not fail next delete for other persistence id" in {

      val pid = randomPid()

      persistForRecovery(pid, List((SnapshotMeta(0), 1), (SnapshotMeta(1), 2), (SnapshotMeta(2), 3)))

      val a = system.actorOf(Props(classOf[A], pid, Some(testActor)))

      val other = randomPid()

      failNextDelete(other)

      a ! DeleteSomeSnapshotByCriteria(SnapshotSelectionCriteria.Latest)

      expectMsg((List.empty, 3))
      expectMsgPF() { case DeleteSnapshotsSuccess(SnapshotSelectionCriteria.Latest) => }

    }

    "clear all" in {

      val pid = randomPid()

      persistForRecovery(pid, List((SnapshotMeta(0), 1), (SnapshotMeta(1), 2), (SnapshotMeta(2), 3)))

      system.actorOf(Props(classOf[A], pid, Some(testActor)))

      expectMsg((List.empty, 3))

      clearAll()

      val aa = system.actorOf(Props(classOf[A], pid, Some(testActor)))

      expectMsg((List.empty, 0))

      aa ! AskSnapshotSeqNum

      expectMsg(0L)

    }

    "clear all for particular persistence id" in {

      val pid = randomPid()

      persistForRecovery(pid, List((SnapshotMeta(0), 1), (SnapshotMeta(1), 2), (SnapshotMeta(2), 3)))

      system.actorOf(Props(classOf[A], pid, Some(testActor)))

      expectMsg((List.empty, 3))

      clearByPersistenceId(pid)

      val aa = system.actorOf(Props(classOf[A], pid, Some(testActor)))

      expectMsg((List.empty, 0))

      aa ! AskSnapshotSeqNum

      expectMsg(0L)

    }

    "preserve all for other persistence id" in {

      val pid = randomPid()

      persistForRecovery(pid, List((SnapshotMeta(0), 1), (SnapshotMeta(1), 2), (SnapshotMeta(2), 3)))

      system.actorOf(Props(classOf[A], pid, Some(testActor)))

      expectMsg((List.empty, 3))

      clearByPersistenceId(randomPid())

      system.actorOf(Props(classOf[A], pid, Some(testActor)))

      expectMsg((List.empty, 3))

    }

    specificTests()
  }

}
