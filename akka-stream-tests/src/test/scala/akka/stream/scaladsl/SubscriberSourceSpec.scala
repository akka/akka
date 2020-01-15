/*
 * Copyright (C) 2014-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.stream.testkit.StreamSpec

import scala.concurrent.Await
import scala.concurrent.duration._

class SubscriberSourceSpec extends StreamSpec {

  "A SubscriberSource" must {

    "be able to use Subscriber in materialized value transformation" in {
      val f =
        Source
          .asSubscriber[Int]
          .mapMaterializedValue(s => Source(1 to 3).runWith(Sink.fromSubscriber(s)))
          .runWith(Sink.fold[Int, Int](0)(_ + _))

      Await.result(f, 3.seconds) should be(6)
    }
  }

}
