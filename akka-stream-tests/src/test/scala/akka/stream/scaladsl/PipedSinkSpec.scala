/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.testkit.Utils._
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.testkit.{ AkkaSpec, _ }
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

class PipedSinkSpec extends AkkaSpec {
  implicit val ec = system.dispatcher

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)
  implicit val mat = ActorMaterializer(settings)
  val ex = new RuntimeException("ex") with NoStackTrace

  "PipedSinkSpec" must {
    "send the elements to the next (piped) flow" in assertAllStagesStopped {
      val source = Source(List(1, 2, 3)).runWith(Sink.pipedSink())
      source.runWith(TestSink.probe[Int])
        .request(1)
        .expectNext(1)
        .request(2)
        .expectNext(2, 3)
        .expectComplete()
    }

    "wait for backpressure" in assertAllStagesStopped {
      val source = Source(List(1, 2, 3)).runWith(Sink.pipedSink())
      source.runWith(TestSink.probe[Int])
        .request(1)
        .expectNext(1)
        .expectNoMsg(200.millis)
        .cancel()
    }

    "cancel first flow when piped is failed" in assertAllStagesStopped {
      val probe = TestPublisher.probe[Int]()
      val source = Source(probe).runWith(Sink.pipedSink())
      val sub = source.map(a ⇒ throw ex).runWith(TestSink.probe[Int])
        .request(1)
      probe.sendNext(1)
      sub.expectError()
      probe.expectCancellation()
    }

    "do nothing when running without piped flow" in assertAllStagesStopped {
      import FlowGraph.Implicits._
      val subscriber = TestSubscriber.manualProbe[Int]()
      val pub = TestPublisher.manualProbe[(Int, String)]()
      FlowGraph.closed() { implicit b ⇒
        val unzip = b.add(Unzip[Int, String]())
        Source(pub) ~> unzip.in
        unzip.out0 ~> Sink(subscriber)
        unzip.out1 ~> Sink.pipedSink[String]()
      }.run()
      val sub = subscriber.expectSubscription()
      val upstream = pub.expectSubscription()
      sub.request(1)
      upstream.sendNext(1 -> "a")
      subscriber.expectNoMsg(200.millis)
      upstream.sendError(ex) //use error to stop incorrect flow
    }

  }
}
