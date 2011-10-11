package akka.dispatch

import akka.actor.{ Props, LocalActorRef, Actor }
import akka.testkit.AkkaSpec

class PriorityDispatcherSpec extends AkkaSpec {

  "A PriorityDispatcher" must {
    "Order it's messages according to the specified comparator using an unbounded mailbox" in {
      testOrdering(UnboundedPriorityMailbox(PriorityGenerator({
        case i: Int  ⇒ i //Reverse order
        case 'Result ⇒ Int.MaxValue
      }: Any ⇒ Int)))
    }

    "Order it's messages according to the specified comparator using a bounded mailbox" in {
      testOrdering(BoundedPriorityMailbox(PriorityGenerator({
        case i: Int  ⇒ i //Reverse order
        case 'Result ⇒ Int.MaxValue
      }: Any ⇒ Int), 1000, app.AkkaConfig.MailboxPushTimeout))
    }
  }

  def testOrdering(mboxType: MailboxType) {
    val dispatcher = app.dispatcherFactory.newDispatcher("Test", 1, -1, mboxType).build

    val actor = createActor(Props(new Actor {
      var acc: List[Int] = Nil

      def receive = {
        case i: Int  ⇒ acc = i :: acc
        case 'Result ⇒ tryReply(acc)
      }
    }).withDispatcher(dispatcher)).asInstanceOf[LocalActorRef]

    actor.suspend //Make sure the actor isn't treating any messages, let it buffer the incoming messages

    val msgs = (1 to 100).toList
    for (m ← msgs) actor ! m

    actor.resume //Signal the actor to start treating it's message backlog

    actor.?('Result).as[List[Int]].get must be === (msgs.reverse)
  }

}
