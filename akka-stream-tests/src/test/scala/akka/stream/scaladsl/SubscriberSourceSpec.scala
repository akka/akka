/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.stream.ActorMaterializer
import akka.stream.testkit.StreamSpec

import scala.concurrent.duration._

import scala.concurrent.Await

class SubscriberSourceSpec extends StreamSpec {

  implicit val materializer = ActorMaterializer()

  "A SubscriberSource" must {

    "be able to use Subscriber in materialized value transformation" in {
      val f =
        Source.asSubscriber[Int].mapMaterializedValue(s ⇒ Source(1 to 3).runWith(Sink.fromSubscriber(s)))
          .runWith(Sink.fold[Int, Int](0)(_ + _))

      Await.result(f, 3.seconds) should be(6)
    }
  }

}
