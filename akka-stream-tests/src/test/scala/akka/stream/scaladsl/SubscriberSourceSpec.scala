/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.ActorMaterializer

import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.Utils._
import scala.concurrent.duration._

import scala.concurrent.Await

class SubscriberSourceSpec extends AkkaSpec {

  implicit val materializer = ActorMaterializer()

  "A SubscriberSource" must {

    "be able to use Subscriber in materialized value transformation" in {
      val f =
        Source.subscriber[Int].mapMaterializedValue(s ⇒ Source(1 to 3).runWith(Sink(s)))
          .runWith(Sink.fold[Int, Int](0)(_ + _))

      Await.result(f, 3.seconds) should be(6)
    }
  }

}
