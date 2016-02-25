/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.testkit

import akka.stream._
import akka.stream.scaladsl.Source
import akka.stream.testkit.scaladsl.TestSink
import scala.concurrent.duration._
import akka.testkit.AkkaSpec

class StreamTestKitSpec extends AkkaSpec {

  implicit val materializer = ActorMaterializer()

  val ex = new Exception("Boom!")

  "A TestSink Probe" must {
    "#toStrict" in {
      Source(1 to 4).runWith(TestSink.probe)
        .toStrict(300.millis) should ===(List(1, 2, 3, 4))
    }

    "#toStrict with failing source" in {
      val error = intercept[AssertionError] {
        Source.fromIterator(() ⇒ new Iterator[Int] {
          var i = 0
          override def hasNext: Boolean = true
          override def next(): Int = {
            i += 1
            i match {
              case 3 ⇒ throw ex
              case n ⇒ n
            }
          }
        }).runWith(TestSink.probe)
          .toStrict(300.millis)
      }

      error.getCause.getMessage should include("Boom!")
      error.getMessage should include("List(1, 2)")
    }

    "#toStrict when subscription was already obtained" in {
      val p = Source(1 to 4).runWith(TestSink.probe)
      p.expectSubscription()
      p.toStrict(300.millis) should ===(List(1, 2, 3, 4))
    }

    "#expectNextOrError with right element" in {
      Source(1 to 4).runWith(TestSink.probe)
        .request(4)
        .expectNextOrError(1, ex)
    }

    "#expectNextOrError with right exception" in {
      Source.failed[Int](ex).runWith(TestSink.probe)
        .request(4)
        .expectNextOrError(1, ex)
    }

    "#expectNextOrError fail if the next element is not the expected one" in {
      intercept[AssertionError] {
        Source(1 to 4).runWith(TestSink.probe)
          .request(4)
          .expectNextOrError(100, ex)
      }.getMessage should include("OnNext(1)")
    }

    "#expectError" in {
      Source.failed[Int](ex).runWith(TestSink.probe)
        .request(1)
        .expectError() should ===(ex)
    }

    "#expectError fail if no error signalled" in {
      intercept[AssertionError] {
        Source(1 to 4).runWith(TestSink.probe)
          .request(1)
          .expectError()
      }.getMessage should include("OnNext")
    }

    "#expectComplete should fail if error signalled" in {
      intercept[AssertionError] {
        Source.failed[Int](ex).runWith(TestSink.probe)
          .request(1)
          .expectComplete()
      }.getMessage should include("OnError")
    }

    "#expectComplete should fail if next element signalled" in {
      intercept[AssertionError] {
        Source(1 to 4).runWith(TestSink.probe)
          .request(1)
          .expectComplete()
      }.getMessage should include("OnNext")
    }

    "#expectNextOrComplete with right element" in {
      Source(1 to 4).runWith(TestSink.probe)
        .request(4)
        .expectNextOrComplete(1)
    }

    "#expectNextOrComplete with completion" in {
      Source.single(1).runWith(TestSink.probe)
        .request(4)
        .expectNextOrComplete(1)
        .expectNextOrComplete(1337)
    }

    "#expectNextN given a number of elements" in {
      Source(1 to 4).runWith(TestSink.probe)
        .request(4)
        .expectNextN(4) should ===(List(1, 2, 3, 4))
    }

    "#expectNextN given specific elements" in {
      Source(1 to 4).runWith(TestSink.probe)
        .request(4)
        .expectNextN(4) should ===(List(1, 2, 3, 4))
    }
  }
}
