/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.{ ClosedShape, ActorMaterializer }

import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.Utils._
import scala.concurrent.duration._

import scala.concurrent.Await

class PublisherSinkSpec extends AkkaSpec {

  implicit val materializer = ActorMaterializer()

  "A PublisherSink" must {

    "be unique when created twice" in assertAllStagesStopped {

      val (pub1, pub2) = RunnableGraph.fromGraph(GraphDSL.create(Sink.publisher[Int](false), Sink.publisher[Int](false))(Keep.both) { implicit b ⇒
        (p1, p2) ⇒
          import GraphDSL.Implicits._

          val bcast = b.add(Broadcast[Int](2))

          Source(0 to 5) ~> bcast.in
          bcast.out(0).map(_ * 2) ~> p1.inlet
          bcast.out(1) ~> p2.inlet
          ClosedShape
      }).run()

      val f1 = Source(pub1).map(identity).runFold(0)(_ + _)
      val f2 = Source(pub2).map(identity).runFold(0)(_ + _)

      Await.result(f1, 3.seconds) should be(30)
      Await.result(f2, 3.seconds) should be(15)

    }

    "work with SubscriberSource" in {
      val (sub, pub) = Source.subscriber[Int].toMat(Sink.publisher(false))(Keep.both).run()
      Source(1 to 100).to(Sink(sub)).run()
      Await.result(Source(pub).grouped(1000).runWith(Sink.head), 3.seconds) should ===(1 to 100)
    }

    "be able to use Publisher in materialized value transformation" in {
      val f = Source(1 to 3).runWith(
        Sink.publisher[Int](false).mapMaterializedValue(p ⇒ Source(p).runFold(0)(_ + _)))

      Await.result(f, 3.seconds) should be(6)
    }
  }

}
