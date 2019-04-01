/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.typed.scaladsl

//#imports
import akka.stream.scaladsl._
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors

import scala.concurrent.duration._
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.WordSpecLike

//#imports
import akka.actor.typed.DispatcherSelector
import akka.stream.testkit.TestSubscriber

import scala.collection.immutable
import scala.concurrent.{ Await, Future }

object ActorFlowSpec {
  final case class Asking(s: String, replyTo: ActorRef[Reply])
  final case class Reply(s: String)
}

class ActorFlowSpec extends ScalaTestWithActorTestKit with WordSpecLike {
  import ActorFlowSpec._

  implicit val mat = ActorMaterializer()

  "ActorFlow" should {

    val replier = spawn(Behaviors.receiveMessage[Asking] {
      case Asking("TERMINATE", _) =>
        Behaviors.stopped

      case asking =>
        asking.replyTo ! Reply(asking.s + "!!!")
        Behaviors.same
    })

    "produce asked elements" in {
      val in: Future[immutable.Seq[Reply]] =
        Source
          .repeat("hello")
          .via(ActorFlow.ask(replier)((el, replyTo: ActorRef[Reply]) => Asking(el, replyTo)))
          .take(3)
          .runWith(Sink.seq)

      in.futureValue shouldEqual List.fill(3)(Reply("hello!!!"))
    }

    "produce asked elements in order" in {
      //#ask-actor
      val ref = spawn(Behaviors.receiveMessage[Asking] { asking =>
        asking.replyTo ! Reply(asking.s + "!!!")
        Behaviors.same
      })

      //#ask-actor

      //#ask
      val in: Future[immutable.Seq[Reply]] =
        Source(1 to 50)
          .map(_.toString)
          .via(ActorFlow.ask(ref)((el, replyTo: ActorRef[Reply]) => Asking(el, replyTo)))
          .runWith(Sink.seq)
      //#ask

      in.futureValue shouldEqual List.tabulate(51)(i => Reply(s"$i!!!")).drop(1)
    }

    "signal ask timeout failure" in {
      import akka.actor.typed.scaladsl.adapter._
      val dontReply = spawn(Behaviors.ignore[Asking])

      val c = TestSubscriber.manualProbe[Reply]()(system.toUntyped)
      implicit val ec = system.dispatchers.lookup(DispatcherSelector.default())
      implicit val timeout = akka.util.Timeout(10.millis)

      Source(1 to 5)
        .map(_ + " nope")
        .via(ActorFlow.ask[String, Asking, Reply](4)(dontReply)(Asking(_, _)))
        .to(Sink.fromSubscriber(c))
        .run()

      c.expectSubscription().request(10)
      c.expectError().getMessage should startWith("Ask timed out on [Actor")
    }

    "signal failure when target actor is terminated" in {
      val done = Source
        .maybe[String]
        .via(ActorFlow.ask(replier)((el, replyTo: ActorRef[Reply]) => Asking(el, replyTo)))
        .runWith(Sink.ignore)

      intercept[RuntimeException] {
        replier ! Asking("TERMINATE", system.deadLetters)
        Await.result(done, 3.seconds)
      }.getMessage should startWith("Actor watched by [ask()] has terminated! Was: Actor[akka://ActorFlowSpec")
    }

  }

}
