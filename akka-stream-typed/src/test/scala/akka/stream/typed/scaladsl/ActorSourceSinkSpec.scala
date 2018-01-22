/*
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.stream.typed.scaladsl

import akka.actor.typed.scaladsl.Actor
import akka.actor.typed.{ ActorRef, TypedAkkaSpec }
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{ Keep, Sink, Source }
import akka.stream.typed.ActorMaterializer
import akka.stream.typed.scaladsl.ActorSourceSinkSpec.AckProto
import akka.testkit.typed.TestKit
import akka.testkit.typed.scaladsl._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }

object ActorSourceSinkSpec {

  sealed trait AckProto
  case class Init(sender: ActorRef[String]) extends AckProto
  case class Msg(sender: ActorRef[String], msg: String) extends AckProto
  case object Complete extends AckProto
  case object Failed extends AckProto
}

class ActorSourceSinkSpec extends TestKit with TypedAkkaSpec with WordSpecLike with BeforeAndAfterAll with Matchers with ScalaFutures {
  import ActorSourceSinkSpec._
  import akka.actor.typed.scaladsl.adapter._

  implicit val mat = ActorMaterializer()

  "ActorSink" should {

    "accept messages" in {
      val p = TestProbe[String]()

      val in =
        Source.queue[String](10, OverflowStrategy.dropBuffer)
          .map(_ + "!")
          .to(ActorSink.actorRef(p.ref, "DONE", ex ⇒ "FAILED: " + ex.getMessage))
          .run()

      val msg = "Zug zug"

      in.offer(msg)
      p.expectMsg(msg + "!")
    }

    "obey protocol" in {
      val p = TestProbe[AckProto]()

      val pilotRef = spawn(Actor.immutable[AckProto] { (ctx, msg) ⇒
        msg match {
          case m @ Init(sender) ⇒
            p.ref ! m
            sender ! "ACK"
            Actor.same
          case m @ Msg(sender, _) ⇒
            p.ref ! m
            sender ! "ACK"
            Actor.same
          case m ⇒
            p.ref ! m
            Actor.same
        }
      })

      val in =
        Source.queue[String](10, OverflowStrategy.dropBuffer)
          .to(ActorSink.actorRefWithAck(pilotRef, Msg.apply, Init.apply, "ACK", Complete, _ ⇒ Failed))
          .run()

      p.expectMsgType[Init]

      in.offer("Dabu!")
      p.expectMsgType[Msg].msg shouldBe "Dabu!"

      in.offer("Lok'tar!")
      p.expectMsgType[Msg].msg shouldBe "Lok'tar!"

      in.offer("Swobu!")
      p.expectMsgType[Msg].msg shouldBe "Swobu!"
    }
  }

  "ActorSource" should {
    "send messages and complete" in {
      val (in, out) = ActorSource.actorRef[String]({ case "complete" ⇒ }, PartialFunction.empty, 10, OverflowStrategy.dropBuffer)
        .toMat(Sink.seq)(Keep.both)
        .run()

      in ! "one"
      in ! "two"
      in ! "complete"

      out.futureValue should contain theSameElementsAs Seq("one", "two")
    }

    "fail the stream" in {
      val (in, out) = ActorSource.actorRef[String](PartialFunction.empty, { case msg ⇒ new Error(msg) }, 10, OverflowStrategy.dropBuffer)
        .toMat(Sink.seq)(Keep.both)
        .run()

      in ! "boom!"

      out.failed.futureValue.getCause.getMessage shouldBe "boom!"
    }
  }

}
