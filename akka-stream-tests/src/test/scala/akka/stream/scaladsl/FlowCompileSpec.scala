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
      val f: Source[Int] = intSeq.connect(Flow[Int])
    }
    "accept Future" in {
      val f: Source[Int] = intFut.connect(Flow[Int])
    }
    "append Flow" in {
      val open1: Flow[Int, String] = Flow[Int].map(_.toString)
      val open2: Flow[String, Int] = Flow[String].map(_.hashCode)
      val open3: Flow[Int, Int] = open1.connect(open2)
      "open3.run()" shouldNot compile

      val closedSource: Source[Int] = intSeq.connect(open3)
      "closedSource.run()" shouldNot compile

      val closedSink: Sink[Int] = open3.connect(Sink.publisher[Int])
      "closedSink.run()" shouldNot compile

      closedSource.connect(Sink.publisher[Int]).run()
      intSeq.connect(closedSink).run()
    }
    "append Sink" in {
      val open: Flow[Int, String] = Flow[Int].map(_.toString)
      val closedSink: Sink[String] = Flow[String].map(_.hashCode).connect(Sink.publisher[Int])
      val appended: Sink[Int] = open.connect(closedSink)
      "appended.run()" shouldNot compile
      "appended.connect(Sink.future[Int])" shouldNot compile
      intSeq.connect(appended).run
    }
    "be appended to Source" in {
      val open: Flow[Int, String] = Flow[Int].map(_.toString)
      val closedSource: Source[Int] = strSeq.connect(Flow[String].map(_.hashCode))
      val closedSource2: Source[String] = closedSource.connect(open)
      "closedSource2.run()" shouldNot compile
      "strSeq.connect(closedSource2)" shouldNot compile
      closedSource2.connect(Sink.publisher[String]).run
    }
  }

  "Sink" should {
    val openSource: Sink[Int] =
      Flow[Int].map(_.toString).connect(Sink.publisher[String])
    "accept Source" in {
      intSeq.connect(openSource)
    }
    "not accept Sink" in {
      "openSource.connect(Sink.future[String])" shouldNot compile
    }
    "not run()" in {
      "openSource.run()" shouldNot compile
    }
  }

  "Source" should {
    val openSource: Source[String] =
      Source(Seq(1, 2, 3)).map(_.toString)
    "accept Sink" in {
      openSource.connect(Sink.publisher[String])
    }
    "not be accepted by Source" in {
      "openSource.connect(intSeq)" shouldNot compile
    }
    "not run()" in {
      "openSource.run()" shouldNot compile
    }
  }

  "RunnableFlow" should {
    Sink.future[String]
    val closed: RunnableFlow =
      Source(Seq(1, 2, 3)).map(_.toString).connect(Sink.publisher[String])
    "run" in {
      closed.run()
    }
    "not be accepted by Source" in {
      "intSeq.connect(closed)" shouldNot compile
    }

    "not accept Sink" in {
      "closed.connect(Sink.future[String])" shouldNot compile
    }
  }
}
