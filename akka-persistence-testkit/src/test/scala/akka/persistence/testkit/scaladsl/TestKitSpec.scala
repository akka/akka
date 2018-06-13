/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.scaladsl

import java.util.UUID

import akka.actor.{ActorSystem, Props}
import akka.persistence.PersistentActor
import com.typesafe.config.ConfigFactory
import org.scalatest.WordSpecLike

class TestKitSpec extends PersistenceTestKit with WordSpecLike {

  override lazy val system = {
    //todo probably implement method for setting plugin in Persistence for testing purposes
    ActorSystem(
      s"persistence-testkit-${UUID.randomUUID()}",
      PersistenceTestKitPlugin.PersitenceTestkitJournalConfig
        .withFallback(PersistenceTestKitSnapshotPlugin.PersitenceTestkitSnapshotStoreConfig)
        .withFallback(ConfigFactory.defaultApplication()))

  }

  "PersistenceTestkit" should {

    "expect next valid message" in {

      val a = system.actorOf(Props(classOf[A], "111"))

      a ! B(1)
      a ! B(2)

      expectNextPersisted("111", B(1))
      expectNextPersisted("111", B(2))

      assertThrows[AssertionError] {
        expectNextPersisted("111", B(3))
      }

    }

    "expect next N valid messages in order" in {

      val a = system.actorOf(Props(classOf[A], "222"))

      a ! B(1)
      a ! B(2)

      expectPersistedInOrder("222", List(B(1), B(2)))

    }

    "expect next N valid messages in any order" in {

      val a = system.actorOf(Props(classOf[A], "333"))

      a ! B(2)
      a ! B(1)

      expectPersistedInAnyOrder("333", List(B(1), B(2)))

    }

    "reject next persisted" in {

      val a = system.actorOf(Props(classOf[A], "111"))

      rejectNextPersisted()

      a ! B(1)

      assertThrows[AssertionError] {
        expectNextPersisted("111", B(1))
      }

       a ! B(1)

      expectNextPersisted("111", B(1))


    }

  }

}

case class B(i: Int)

class A(pid: String) extends PersistentActor {
  override def receiveRecover = {
    case s ⇒ println(s)
  }

  override def receiveCommand = {
    case s ⇒
      persist(s)(println)
      sender() ! s
  }

  override def persistenceId = pid
}
