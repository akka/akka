/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.collection.immutable.Seq
import scala.concurrent.Future

import akka.stream.FlowMaterializer
import akka.stream.MaterializerSettings
import akka.stream.testkit.AkkaSpec

class FlowCompileSpec extends AkkaSpec {

  val intSeq = Source(Seq(1, 2, 3))
  val strSeq = Source(Seq("a", "b", "c"))

  import scala.concurrent.ExecutionContext.Implicits.global
  val intFut = Source(Future { 3 })
  implicit val materializer = FlowMaterializer(MaterializerSettings(system))

  "Flow" should {
    "should not run" in {
      val open: Flow[Int, Int] = Flow[Int]
      "open.run()" shouldNot compile
    }
    "accept Iterable" in {
      val f: Source[Int] = intSeq.via(Flow[Int])
    }
    "accept Future" in {
      val f: Source[Int] = intFut.via(Flow[Int])
    }
    "append Flow" in {
      val open1: Flow[Int, String] = Flow[Int].map(_.toString)
      val open2: Flow[String, Int] = Flow[String].map(_.hashCode)
      val open3: Flow[Int, Int] = open1.via(open2)
      "open3.run()" shouldNot compile

      val closedSource: Source[Int] = intSeq.via(open3)
      "closedSource.run()" shouldNot compile

      val closedSink: Sink[Int] = open3.to(Sink.publisher[Int])
      "closedSink.run()" shouldNot compile

      closedSource.to(Sink.publisher[Int]).run()
      intSeq.to(closedSink).run()
    }
    "append Sink" in {
      val open: Flow[Int, String] = Flow[Int].map(_.toString)
      val closedSink: Sink[String] = Flow[String].map(_.hashCode).to(Sink.publisher[Int])
      val appended: Sink[Int] = open.to(closedSink)
      "appended.run()" shouldNot compile
      "appended.connect(Sink.head[Int])" shouldNot compile
      intSeq.to(appended).run
    }
    "be appended to Source" in {
      val open: Flow[Int, String] = Flow[Int].map(_.toString)
      val closedSource: Source[Int] = strSeq.via(Flow[String].map(_.hashCode))
      val closedSource2: Source[String] = closedSource.via(open)
      "closedSource2.run()" shouldNot compile
      "strSeq.connect(closedSource2)" shouldNot compile
      closedSource2.to(Sink.publisher[String]).run
    }
  }

  "Sink" should {
    val openSource: Sink[Int] =
      Flow[Int].map(_.toString).to(Sink.publisher[String])
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
    val openSource: Source[String] =
      Source(Seq(1, 2, 3)).map(_.toString)
    "accept Sink" in {
      openSource.to(Sink.publisher[String])
    }
    "not be accepted by Source" in {
      "openSource.connect(intSeq)" shouldNot compile
    }
    "not run()" in {
      "openSource.run()" shouldNot compile
    }
  }

  "RunnableFlow" should {
    Sink.head[String]
    val closed: RunnableFlow =
      Source(Seq(1, 2, 3)).map(_.toString).to(Sink.publisher[String])
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
