package akka.persistence.testkit

import akka.actor.Props
import akka.persistence.PersistentActor
import akka.testkit.ImplicitSender
import org.scalatest.WordSpecLike

class JournalSpec extends PersistenceTestKitImpl with WordSpecLike with ImplicitSender {

  "this spec" should {

    "init journal" in {

      val a = system.actorOf(Props[A])

      a ! B(1)
      Thread.sleep(1000)

      expectPersisted(B(1))

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
