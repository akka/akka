package akka.dispatch

import akka.actor.Actor._
import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import akka.actor.{ Props, LocalActorRef, Actor }

class PriorityDispatcherSpec extends WordSpec with MustMatchers {

  "A PriorityDispatcher" must {
    "Order it's messages according to the specified comparator using an unbounded mailbox" in {
      testOrdering(UnboundedMailbox())
    }

    "Order it's messages according to the specified comparator using a bounded mailbox" in {
      testOrdering(BoundedMailbox(1000))
    }
  }

  def testOrdering(mboxType: MailboxType) {
    val dispatcher = new PriorityDispatcher("Test",
      PriorityGenerator({
        case i: Int  ⇒ i //Reverse order
        case 'Result ⇒ Int.MaxValue
      }: Any ⇒ Int),
      throughput = 1,
      mailboxType = mboxType)

    val actor = actorOf(Props(new Actor {
      var acc: List[Int] = Nil

      def receive = {
        case i: Int  ⇒ acc = i :: acc
        case 'Result ⇒ self tryReply acc
      }
    }).withDispatcher(dispatcher)).asInstanceOf[LocalActorRef]

    actor.suspend //Make sure the actor isn't treating any messages, let it buffer the incoming messages

    val msgs = (1 to 100).toList
    for (m ← msgs) actor ! m

    actor.resume //Signal the actor to start treating it's message backlog

    actor.?('Result).as[List[Int]].get must be === (msgs.reverse)
  }

}
