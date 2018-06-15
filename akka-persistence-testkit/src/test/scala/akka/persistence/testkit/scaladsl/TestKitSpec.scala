/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.scaladsl

import java.util.UUID

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.persistence.{PersistentActor, RecoveryCompleted}
import akka.testkit.TestKitBase
import com.typesafe.config.ConfigFactory
import org.scalatest.WordSpecLike

class TestKitSpec extends PersistenceTestKit with WordSpecLike with TestKitBase {

  override lazy val system = {
    //todo probably implement method for setting plugin in Persistence for testing purposes
    ActorSystem(
      s"persistence-testkit-${UUID.randomUUID()}",
      PersistenceTestKitPlugin.PersitenceTestkitJournalConfig
        //.withFallback(PersistenceTestKitSnapshotPlugin.PersitenceTestkitSnapshotStoreConfig)
        .withFallback(ConfigFactory.defaultApplication()))

  }

  "PersistenceTestkit" should {

    "expect next valid message" in {

      val pid = randomPid()

      val a = system.actorOf(Props(classOf[A], pid, None))

      a ! B(1)
      a ! B(2)

      expectNextPersisted(pid, B(1))

      assertThrows[AssertionError] {
        expectNextPersisted(pid, B(3))
      }

      expectNextPersisted(pid, B(2))

      assertThrows[AssertionError] {
        expectNextPersisted(pid, B(3))
      }

    }

    "expect next N valid messages in order" in {

      val pid = randomPid()

      val a = system.actorOf(Props(classOf[A], pid, None))

      a ! B(1)
      a ! B(2)

      assertThrows[AssertionError]{
        expectPersistedInOrder(pid, List(B(2), B(1)))
      }

      expectPersistedInOrder(pid, List(B(1), B(2)))

    }

    "expect next N valid messages in any order" in {

      val pid = randomPid()

      val a = system.actorOf(Props(classOf[A], pid, None))

      a ! B(2)
      a ! B(1)

      assertThrows[AssertionError]{
        expectPersistedInAnyOrder(pid, List(B(3), B(2)))
      }

      expectPersistedInAnyOrder(pid, List(B(1), B(2)))

    }

    "reject next persisted" in {

      val pid = randomPid()

      val a = system.actorOf(Props(classOf[A], pid, None))


      //consecutive calls should stack
      rejectNextPersisted()
      rejectNextPersisted()

      a ! B(1)

      assertThrows[AssertionError] {
        expectNextPersisted(pid, B(1))
      }

      a ! B(1)

      assertThrows[AssertionError] {
        expectNextPersisted(pid, B(1))
      }

      a ! B(1)

      expectNextPersisted(pid, B(1))

    }

    "fail next persisted" in {

      val pid = randomPid()

      val a = system.actorOf(Props(classOf[A], pid, None))

      failNextPersisted()

      a ! B(1)

      watch(a)
      expectTerminated(a)

      val b = system.actorOf(Props(classOf[A], pid, None))

      b ! B(1)

      expectNextPersisted(pid, B(1))

    }

    "expect no message persisted" in {

      val pid = randomPid()

      expectNoMessagePersisted(pid)

      val a = system.actorOf(Props(classOf[A], pid, None))

      a ! B(1)

      assertThrows[AssertionError]{
        expectNoMessagePersisted(pid)
      }

    }

    "recover persisted messages" in {

      val preload = List(B(1), B(2), B(3))
      val pid = randomPid()

      persistForRecovery(pid, preload)

      system.actorOf(Props(classOf[A], pid, Some(testActor)))

      expectMsg(preload)


    }

  }

  private def randomPid() = UUID.randomUUID().toString

}

case class B(i: Int)

class A(pid: String, respondOnRecover: Option[ActorRef]) extends PersistentActor {
  import scala.collection.immutable

  var recovered = immutable.List.empty[Any]

  override def receiveRecover = {
    case RecoveryCompleted => respondOnRecover.foreach(_  ! recovered)
    case s ⇒ recovered :+= s
  }

  override def receiveCommand = {
    case s ⇒
      persist(s)(println)
      sender() ! s
  }

  override def persistenceId = pid
}
