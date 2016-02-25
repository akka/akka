/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.stream._
import akka.stream.testkit._
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._
import akka.testkit.AkkaSpec

class GraphMatValueSpec extends AkkaSpec {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val materializer = ActorMaterializer(settings)

  import GraphDSL.Implicits._

  "A Graph with materialized value" must {

    val foldSink = Sink.fold[Int, Int](0)(_ + _)

    "expose the materialized value as source" in {
      val sub = TestSubscriber.manualProbe[Int]()
      val f = RunnableGraph.fromGraph(GraphDSL.create(foldSink) { implicit b ⇒
        fold ⇒
          Source(1 to 10) ~> fold
          b.materializedValue.mapAsync(4)(identity) ~> Sink.fromSubscriber(sub)
          ClosedShape
      }).run()

      val r1 = Await.result(f, 3.seconds)
      sub.expectSubscription().request(1)
      val r2 = sub.expectNext()

      r1 should ===(r2)
    }

    "expose the materialized value as source multiple times" in {
      val sub = TestSubscriber.manualProbe[Int]()

      val f = RunnableGraph.fromGraph(GraphDSL.create(foldSink) { implicit b ⇒
        fold ⇒
          val zip = b.add(ZipWith[Int, Int, Int](_ + _))
          Source(1 to 10) ~> fold
          b.materializedValue.mapAsync(4)(identity) ~> zip.in0
          b.materializedValue.mapAsync(4)(identity) ~> zip.in1

          zip.out ~> Sink.fromSubscriber(sub)
          ClosedShape
      }).run()

      val r1 = Await.result(f, 3.seconds)
      sub.expectSubscription().request(1)
      val r2 = sub.expectNext()

      r1 should ===(r2 / 2)
    }

    // Exposes the materialized value as a stream value
    val foldFeedbackSource: Source[Future[Int], Future[Int]] = Source.fromGraph(GraphDSL.create(foldSink) { implicit b ⇒
      fold ⇒
        Source(1 to 10) ~> fold
        SourceShape(b.materializedValue)
    })

    "allow exposing the materialized value as port" in {
      val (f1, f2) = foldFeedbackSource.mapAsync(4)(identity).map(_ + 100).toMat(Sink.head)(Keep.both).run()
      Await.result(f1, 3.seconds) should ===(55)
      Await.result(f2, 3.seconds) should ===(155)
    }

    "allow exposing the materialized value as port even if wrapped and the final materialized value is Unit" in {
      val noMatSource: Source[Int, Unit] = foldFeedbackSource.mapAsync(4)(identity).map(_ + 100).mapMaterializedValue((_) ⇒ ())
      Await.result(noMatSource.runWith(Sink.head), 3.seconds) should ===(155)
    }

    "work properly with nesting and reusing" in {
      val compositeSource1 = Source.fromGraph(GraphDSL.create(foldFeedbackSource, foldFeedbackSource)(Keep.both) { implicit b ⇒
        (s1, s2) ⇒
          val zip = b.add(ZipWith[Int, Int, Int](_ + _))

          s1.out.mapAsync(4)(identity) ~> zip.in0
          s2.out.mapAsync(4)(identity).map(_ * 100) ~> zip.in1
          SourceShape(zip.out)
      })

      val compositeSource2 = Source.fromGraph(GraphDSL.create(compositeSource1, compositeSource1)(Keep.both) { implicit b ⇒
        (s1, s2) ⇒
          val zip = b.add(ZipWith[Int, Int, Int](_ + _))
          s1.out ~> zip.in0
          s2.out.map(_ * 10000) ~> zip.in1
          SourceShape(zip.out)
      })

      val (((f1, f2), (f3, f4)), result) = compositeSource2.toMat(Sink.head)(Keep.both).run()

      Await.result(result, 3.seconds) should ===(55555555)
      Await.result(f1, 3.seconds) should ===(55)
      Await.result(f2, 3.seconds) should ===(55)
      Await.result(f3, 3.seconds) should ===(55)
      Await.result(f4, 3.seconds) should ===(55)

    }

    "work also when the source’s module is copied" in {
      val foldFlow: Flow[Int, Int, Future[Int]] = Flow.fromGraph(GraphDSL.create(Sink.fold[Int, Int](0)(_ + _)) {
        implicit builder ⇒
          fold ⇒
            FlowShape(fold.in, builder.materializedValue.mapAsync(4)(identity).outlet)
      })

      Await.result(Source(1 to 10).via(foldFlow).runWith(Sink.head), 3.seconds) should ===(55)
    }

  }
}
