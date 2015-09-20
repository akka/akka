/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.{ StreamTcpException, ActorMaterializer }

import akka.stream.testkit.{ TestPublisher, TestSubscriber, AkkaSpec }
import akka.stream.testkit.Utils._
import scala.concurrent.duration._

import scala.concurrent.Await

class SubscriberSourceSpec extends AkkaSpec("akka.loglevel=DEBUG\nakka.actor.debug.lifecycle=on") {

  implicit val materializer = ActorMaterializer()

  "A SubscriberSource" must {

    "be able to use Subscriber in materialized value transformation" in {
      val f =
        Source.subscriber[Int].mapMaterializedValue(s ⇒ Source(1 to 3).runWith(Sink(s)))
          .runWith(Sink.fold[Int, Int](0)(_ + _))

      Await.result(f, 3.seconds) should be(6)
    }

    "throw exception if subscribe more then once" in {
      val sub = TestSubscriber.manualProbe[String]
      val sub1 = Source.subscriber[String].to(Sink(sub)).run()
      val source = Source.apply(List("1", "2", "3"))

      val pub1 = source.runWith(Sink.publisher)
      val pub2 = source.runWith(Sink.publisher)

      pub1.subscribe(sub1)
      val s = sub.expectSubscription()

      a[IllegalStateException] should be thrownBy pub2.subscribe(sub1)

      s.request(3)
      sub.expectNext("1", "2", "3")
      sub.expectComplete()
    }
  }

}
