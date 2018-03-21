package akka.persistence.testkit.scaladsl

import akka.actor.Props
import akka.persistence.PersistentActor
import akka.testkit.ImplicitSender
import org.scalatest.WordSpecLike

class JournalSpec extends PersistenceTestKit with WordSpecLike {

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

      a ! B(1)
      a ! B(2)

      expectPersistedInAnyOrder("333", List(B(1), B(2)))

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
