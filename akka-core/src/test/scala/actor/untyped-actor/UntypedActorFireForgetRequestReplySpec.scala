package se.scalablesolutions.akka.actor

import java.util.concurrent.{TimeUnit, CyclicBarrier, TimeoutException}

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

import se.scalablesolutions.akka.dispatch.Dispatchers
import Actor._

class UntypedActorFireForgetRequestReplySpec extends WordSpec with MustMatchers {

  "An UntypedActor" should {
    "reply to message sent with 'sendOneWay' using 'reply'" in {
      UntypedActorTestState.finished = new CyclicBarrier(2);
      UntypedActorTestState.log = "NIL";
      val replyActor = UntypedActor.actorOf(classOf[ReplyUntypedActor]).start
      val senderActor = UntypedActor.actorOf(classOf[SenderUntypedActor]).start
      senderActor.sendOneWay(replyActor)
      senderActor.sendOneWay("ReplyToSendOneWayUsingReply")
      try { UntypedActorTestState.finished.await(1L, TimeUnit.SECONDS) }
      catch { case e: TimeoutException => fail("Never got the message") }
      UntypedActorTestState.log must be ("Reply")
    }

    "reply to message sent with 'sendOneWay' using 'sender' reference" in {
      UntypedActorTestState.finished = new CyclicBarrier(2);
      UntypedActorTestState.log = "NIL";
      val replyActor = UntypedActor.actorOf(classOf[ReplyUntypedActor]).start
      val senderActor = UntypedActor.actorOf(classOf[SenderUntypedActor]).start
      senderActor.sendOneWay(replyActor)
      senderActor.sendOneWay("ReplyToSendOneWayUsingSender")
      try { UntypedActorTestState.finished.await(1L, TimeUnit.SECONDS) }
      catch { case e: TimeoutException => fail("Never got the message") }
      UntypedActorTestState.log must be ("Reply")
    }

    "reply to message sent with 'sendRequestReply' using 'reply'" in {
      UntypedActorTestState.finished = new CyclicBarrier(2);
      UntypedActorTestState.log = "NIL";
      val replyActor = UntypedActor.actorOf(classOf[ReplyUntypedActor]).start
      val senderActor = UntypedActor.actorOf(classOf[SenderUntypedActor]).start
      senderActor.sendOneWay(replyActor)
      senderActor.sendOneWay("ReplyToSendRequestReplyUsingReply")
      try { UntypedActorTestState.finished.await(1L, TimeUnit.SECONDS) }
      catch { case e: TimeoutException => fail("Never got the message") }
      UntypedActorTestState.log must be ("Reply")
    }

    "reply to message sent with 'sendRequestReply' using 'sender future' reference" in {
      UntypedActorTestState.finished = new CyclicBarrier(2);
      UntypedActorTestState.log = "NIL";
      val replyActor = UntypedActor.actorOf(classOf[ReplyUntypedActor]).start
      val senderActor = UntypedActor.actorOf(classOf[SenderUntypedActor]).start
      senderActor.sendOneWay(replyActor)
      senderActor.sendOneWay("ReplyToSendRequestReplyUsingFuture")
      try { UntypedActorTestState.finished.await(1L, TimeUnit.SECONDS) }
      catch { case e: TimeoutException => fail("Never got the message") }
      UntypedActorTestState.log must be ("Reply")
    }

    "reply to message sent with 'sendRequestReplyFuture' using 'reply'" in {
      UntypedActorTestState.finished = new CyclicBarrier(2);
      UntypedActorTestState.log = "NIL";
      val replyActor = UntypedActor.actorOf(classOf[ReplyUntypedActor]).start
      val senderActor = UntypedActor.actorOf(classOf[SenderUntypedActor]).start
      senderActor.sendOneWay(replyActor)
      senderActor.sendOneWay("ReplyToSendRequestReplyFutureUsingReply")
      try { UntypedActorTestState.finished.await(1L, TimeUnit.SECONDS) }
      catch { case e: TimeoutException => fail("Never got the message") }
      UntypedActorTestState.log must be ("Reply")
    }

    "reply to message sent with 'sendRequestReplyFuture' using 'sender future' reference" in {
      UntypedActorTestState.finished = new CyclicBarrier(2);
      UntypedActorTestState.log = "NIL";
      val replyActor = UntypedActor.actorOf(classOf[ReplyUntypedActor]).start
      val senderActor = UntypedActor.actorOf(classOf[SenderUntypedActor]).start
      senderActor.sendOneWay(replyActor)
      senderActor.sendOneWay("ReplyToSendRequestReplyFutureUsingFuture")
      try { UntypedActorTestState.finished.await(1L, TimeUnit.SECONDS) }
      catch { case e: TimeoutException => fail("Never got the message") }
      UntypedActorTestState.log must be ("Reply")
    }
  }
}
