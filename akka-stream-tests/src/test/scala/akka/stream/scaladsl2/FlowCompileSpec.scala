/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import akka.stream.MaterializerSettings
import akka.stream.testkit.AkkaSpec

import scala.collection.immutable.Seq
import scala.concurrent.Future

class FlowCompileSpec extends AkkaSpec {

  val intSeq = IterableSource(Seq(1, 2, 3))
  val strSeq = IterableSource(Seq("a", "b", "c"))

  import scala.concurrent.ExecutionContext.Implicits.global
  val intFut = FutureSource(Future { 3 })
  implicit val materializer = FlowMaterializer(MaterializerSettings(system))

  "ProcessorFlow" should {
    "should not run" in {
      val open: ProcessorFlow[Int, Int] = FlowFrom[Int]
      "open.run()" shouldNot compile
    }
    "accept IterableSource" in {
      val f: FlowWithSource[Int] = FlowFrom[Int].withSource(intSeq)
    }
    "accept FutureSource" in {
      val f: FlowWithSource[Int] = FlowFrom[Int].withSource(intFut)
    }
    "append ProcessorFlow" in {
      val open1: ProcessorFlow[Int, String] = FlowFrom[Int].map(_.toString)
      val open2: ProcessorFlow[String, Int] = FlowFrom[String].map(_.hashCode)
      val open3: ProcessorFlow[Int, Int] = open1.append(open2)
      "open3.run()" shouldNot compile

      val closedSource: FlowWithSource[Int] = open3.withSource(intSeq)
      "closedSource.run()" shouldNot compile

      val closedSink: FlowWithSink[Int] = open3.withSink(PublisherSink[Int])
      "closedSink.run()" shouldNot compile

      closedSource.withSink(PublisherSink[Int]).run()
      closedSink.withSource(intSeq).run()
    }
    "prepend ProcessorFlow" in {
      val open1: ProcessorFlow[Int, String] = FlowFrom[Int].map(_.toString)
      val open2: ProcessorFlow[String, Int] = FlowFrom[String].map(_.hashCode)
      val open3: ProcessorFlow[String, String] = open1.prepend(open2)
      "open3.run()" shouldNot compile

      val closedSource: FlowWithSource[String] = open3.withSource(strSeq)
      "closedSource.run()" shouldNot compile

      val closedSink: FlowWithSink[String] = open3.withSink(PublisherSink[String])
      "closedSink.run()" shouldNot compile

      closedSource.withSink(PublisherSink[String]).run
      closedSink.withSource(strSeq).run
    }
    "append FlowWithSink" in {
      val open: ProcessorFlow[Int, String] = FlowFrom[Int].map(_.toString)
      val closedSink: FlowWithSink[String] = FlowFrom[String].map(_.hashCode).withSink(PublisherSink[Int])
      val appended: FlowWithSink[Int] = open.append(closedSink)
      "appended.run()" shouldNot compile
      "appended.toFuture" shouldNot compile
      appended.withSource(intSeq).run
    }
    "prepend FlowWithSource" in {
      val open: ProcessorFlow[Int, String] = FlowFrom[Int].map(_.toString)
      val closedSource: FlowWithSource[Int] = FlowFrom[String].map(_.hashCode).withSource(strSeq)
      val prepended: FlowWithSource[String] = open.prepend(closedSource)
      "prepended.run()" shouldNot compile
      "prepended.withSource(strSeq)" shouldNot compile
      prepended.withSink(PublisherSink[String]).run
    }
  }

  "FlowWithSink" should {
    val openSource: FlowWithSink[Int] =
      FlowFrom[Int].map(_.toString).withSink(PublisherSink[String])
    "accept Source" in {
      openSource.withSource(intSeq)
    }
    "not accept Sink" in {
      "openSource.ToFuture" shouldNot compile
    }
    "not run()" in {
      "openSource.run()" shouldNot compile
    }
  }

  "FlowWithSource" should {
    val openSink: FlowWithSource[String] =
      FlowFrom(Seq(1, 2, 3)).map(_.toString)
    "accept Sink" in {
      openSink.withSink(PublisherSink[String])
    }
    "not accept Source" in {
      "openSink.withSource(intSeq)" shouldNot compile
    }
    "not run()" in {
      "openSink.run()" shouldNot compile
    }
  }

  "RunnableFlow" should {
    val closed: RunnableFlow =
      FlowFrom(Seq(1, 2, 3)).map(_.toString).withSink(PublisherSink[String])
    "run" in {
      closed.run()
    }
    "not accept Source" in {
      "closed.withSource(intSeq)" shouldNot compile
    }
    "not accept Sink" in {
      "closed.ToFuture" shouldNot compile
    }
  }

}
