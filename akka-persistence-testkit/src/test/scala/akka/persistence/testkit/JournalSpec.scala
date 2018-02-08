package akka.persistence.testkit

import akka.actor.Props
import akka.persistence.PersistentActor
import akka.testkit.ImplicitSender
import org.scalatest.WordSpecLike

class JournalSpec extends PersistenceTestKit with WordSpecLike with ImplicitSender {

  "PersistenceTestkit" should {

    "expect next valid message" in {

      val a = system.actorOf(Props[A])

      a ! B(1)
      a ! B(2)

      expectNextPersisted("111", B(1))
      expectNextPersisted("111",B(2))

      assertThrows[AssertionError] {
        expectNextPersisted("111", B(3))
      }

    }

  }

}

case class B(i: Int)

class A extends PersistentActor {
  override def receiveRecover = {
    case s ⇒ println(s)
  }

  override def receiveCommand = {
    case s ⇒
      persist(s)(println)
      sender() ! s
  }

  override def persistenceId = "111"
}
