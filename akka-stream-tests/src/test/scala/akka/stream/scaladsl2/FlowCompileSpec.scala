/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import akka.stream.MaterializerSettings
import akka.stream.testkit.AkkaSpec
import scala.collection.immutable.Seq
import scala.concurrent.Future

class FlowCompileSpec extends AkkaSpec {

  val intSeq = IterableTap(Seq(1, 2, 3))
  val strSeq = IterableTap(Seq("a", "b", "c"))

  import scala.concurrent.ExecutionContext.Implicits.global
  val intFut = FutureTap(Future { 3 })
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

      val closedSink: Sink[Int] = open3.connect(PublisherDrain[Int])
      "closedSink.run()" shouldNot compile

      closedSource.connect(PublisherDrain[Int]).run()
      intSeq.connect(closedSink).run()
    }
    "append Sink" in {
      val open: Flow[Int, String] = Flow[Int].map(_.toString)
      val closedDrain: Sink[String] = Flow[String].map(_.hashCode).connect(PublisherDrain[Int])
      val appended: Sink[Int] = open.connect(closedDrain)
      "appended.run()" shouldNot compile
      "appended.connect(FutureDrain[Int])" shouldNot compile
      intSeq.connect(appended).run
    }
    "be appended to Source" in {
      val open: Flow[Int, String] = Flow[Int].map(_.toString)
      val closedTap: Source[Int] = strSeq.connect(Flow[String].map(_.hashCode))
      val closedSource: Source[String] = closedTap.connect(open)
      "closedSource.run()" shouldNot compile
      "strSeq.connect(closedSource)" shouldNot compile
      closedSource.connect(PublisherDrain[String]).run
    }
  }

  "Sink" should {
    val openSource: Sink[Int] =
      Flow[Int].map(_.toString).connect(PublisherDrain[String])
    "accept Source" in {
      intSeq.connect(openSource)
    }
    "not accept Sink" in {
      "openSource.connect(FutureDrain[String])" shouldNot compile
    }
    "not run()" in {
      "openSource.run()" shouldNot compile
    }
  }

  "Source" should {
    val openSource: Source[String] =
      Source(Seq(1, 2, 3)).map(_.toString)
    "accept Sink" in {
      openSource.connect(PublisherDrain[String])
    }
    "not be accepted by Source" in {
      "openSource.connect(intSeq)" shouldNot compile
    }
    "not run()" in {
      "openSource.run()" shouldNot compile
    }
  }

  "RunnableFlow" should {
    FutureDrain[String]
    val closed: RunnableFlow =
      Source(Seq(1, 2, 3)).map(_.toString).connect(PublisherDrain[String])
    "run" in {
      closed.run()
    }
    "not be accepted by Source" in {
      "intSeq.connect(closed)" shouldNot compile
    }

    "not accept Sink" in {
      "closed.connect(FutureDrain[String])" shouldNot compile
    }
  }
}
