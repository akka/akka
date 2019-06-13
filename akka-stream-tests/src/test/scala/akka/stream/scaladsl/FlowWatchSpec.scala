/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.actor.{ Actor, PoisonPill, Props }
import akka.stream.ActorMaterializer
import akka.stream.testkit.scaladsl.StreamTestKit._
import akka.stream.testkit._
import akka.testkit.TestActors

import scala.concurrent.Await
import scala.concurrent.duration._

object FlowWatchSpec {
  case class Reply(payload: Int)

  class Replier extends Actor {
    override def receive: Receive = {
      case msg: Int => sender() ! Reply(msg)
    }
  }

}

class FlowWatchSpec extends StreamSpec {
  import FlowWatchSpec._

  implicit val materializer = ActorMaterializer()

  "A Flow with watch" must {

    val replyOnInts =
      system.actorOf(Props(classOf[Replier]).withDispatcher("akka.test.stream-dispatcher"), "replyOnInts")

    val dontReply = system.actorOf(TestActors.blackholeProps.withDispatcher("akka.test.stream-dispatcher"), "dontReply")

    "pass through elements while actor is alive" in assertAllStagesStopped {
      val c = TestSubscriber.manualProbe[Int]()
      Source(1 to 3).watch(replyOnInts).runWith(Sink.fromSubscriber(c))
      val sub = c.expectSubscription()
      sub.request(2)
      c.expectNext(1)
      c.expectNext(2)
      c.expectNoMessage(200.millis)
      sub.request(2)
      c.expectNext(3)
      c.expectComplete()
    }

    "signal failure when target actor is terminated" in assertAllStagesStopped {
      val r = system.actorOf(Props(classOf[Replier]).withDispatcher("akka.test.stream-dispatcher"), "wanna-fail")
      val done = Source.maybe[Int].watch(r).runWith(Sink.ignore)

      intercept[RuntimeException] {
        r ! PoisonPill
        Await.result(done, remainingOrDefault)
      }.getMessage should startWith(
        "Actor watched by [Watch] has terminated! Was: Actor[akka://FlowWatchSpec/user/wanna-fail#")
    }

    "should handle cancel properly" in assertAllStagesStopped {
      val pub = TestPublisher.manualProbe[Int]()
      val sub = TestSubscriber.manualProbe[Int]()

      Source.fromPublisher(pub).watch(dontReply).runWith(Sink.fromSubscriber(sub))

      val upstream = pub.expectSubscription()
      upstream.expectRequest()

      sub.expectSubscription().cancel()

      upstream.expectCancellation()

    }

  }
}
