/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import org.reactivestreams.Publisher

import scala.collection.immutable.Seq
import scala.concurrent.Future

import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.stream.testkit.AkkaSpec

class FlowCompileSpec extends AkkaSpec {

  val intSeq = Source(Seq(1, 2, 3))
  val strSeq = Source(Seq("a", "b", "c"))

  import scala.concurrent.ExecutionContext.Implicits.global
  val intFut = Source.fromFuture(Future { 3 })
  implicit val materializer = ActorMaterializer(ActorMaterializerSettings(system))

  "Flow" should {
    "should not run" in {
      val open: Flow[Int, Int, _] = Flow[Int]
      "open.run()" shouldNot compile
    }
    "accept Iterable" in {
      val f: Source[Int, _] = intSeq.via(Flow[Int])
    }
    "accept Future" in {
      val f: Source[Int, _] = intFut.via(Flow[Int])
    }
    "append Flow" in {
      val open1: Flow[Int, String, _] = Flow[Int].map(_.toString)
      val open2: Flow[String, Int, _] = Flow[String].map(_.hashCode)
      val open3: Flow[Int, Int, _] = open1.via(open2)
      "open3.run()" shouldNot compile

      val closedSource: Source[Int, _] = intSeq.via(open3)
      "closedSource.run()" shouldNot compile

      val closedSink: Sink[Int, _] = open3.to(Sink.asPublisher[Int](false))
      "closedSink.run()" shouldNot compile

      closedSource.to(Sink.asPublisher[Int](false)).run()
      intSeq.to(closedSink).run()
    }
    "append Sink" in {
      val open: Flow[Int, String, _] = Flow[Int].map(_.toString)
      val closedSink: Sink[String, _] = Flow[String].map(_.hashCode).to(Sink.asPublisher[Int](false))
      val appended: Sink[Int, _] = open.to(closedSink)
      "appended.run()" shouldNot compile
      "appended.connect(Sink.head[Int])" shouldNot compile
      intSeq.to(appended).run
    }
    "be appended to Source" in {
      val open: Flow[Int, String, _] = Flow[Int].map(_.toString)
      val closedSource: Source[Int, _] = strSeq.via(Flow[String].map(_.hashCode))
      val closedSource2: Source[String, _] = closedSource.via(open)
      "closedSource2.run()" shouldNot compile
      "strSeq.connect(closedSource2)" shouldNot compile
      closedSource2.to(Sink.asPublisher[String](false)).run
    }
  }

  "Sink" should {
    val openSource: Sink[Int, _] =
      Flow[Int].map(_.toString).to(Sink.asPublisher[String](false))
    "accept Source" in {
      intSeq.to(openSource)
    }
    "not accept Sink" in {
      "openSource.connect(Sink.head[String])" shouldNot compile
    }
    "not run()" in {
      "openSource.run()" shouldNot compile
    }
  }

  "Source" should {
    val openSource: Source[String, _] =
      Source(Seq(1, 2, 3)).map(_.toString)
    "accept Sink" in {
      openSource.to(Sink.asPublisher[String](false))
    }
    "not be accepted by Source" in {
      "openSource.connect(intSeq)" shouldNot compile
    }
    "not run()" in {
      "openSource.run()" shouldNot compile
    }
  }

  "RunnableGraph" should {
    Sink.head[String]
    val closed: RunnableGraph[Publisher[String]] =
      Source(Seq(1, 2, 3)).map(_.toString).toMat(Sink.asPublisher[String](false))(Keep.right)
    "run" in {
      closed.run()
    }
    "not be accepted by Source" in {
      "intSeq.connect(closed)" shouldNot compile
    }

    "not accept Sink" in {
      "closed.connect(Sink.head[String])" shouldNot compile
    }
  }
}
