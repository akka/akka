/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import akka.stream.MaterializerSettings
import akka.stream.testkit.AkkaSpec

import scala.collection.immutable.Seq
import scala.concurrent.Future

class FlowSpec extends AkkaSpec {

  val intSeq = IterableIn(Seq(1, 2, 3))
  val strSeq = IterableIn(Seq("a", "b", "c"))

  import scala.concurrent.ExecutionContext.Implicits.global
  val intFut = FutureIn(Future { 3 })
  implicit val materializer = FlowMaterializer(MaterializerSettings(system))

  "ProcessorFlow" should {
    "go through all states" in {
      val f: ProcessorFlow[Int, Int] = FlowFrom[Int]
        .withInput(intSeq)
        .withOutput(PublisherOut())
        .withoutInput
        .withoutOutput
    }
    "should not run" in {
      val open: ProcessorFlow[Int, Int] = FlowFrom[Int]
      "open.run()" shouldNot compile
    }
    "accept IterableIn" in {
      val f: PublisherFlow[Int, Int] = FlowFrom[Int].withInput(intSeq)
    }
    "accept FutureIn" in {
      val f: PublisherFlow[Int, Int] = FlowFrom[Int].withInput(intFut)
    }
    "append ProcessorFlow" in {
      val open1: ProcessorFlow[Int, String] = FlowFrom[Int].map(_.toString)
      val open2: ProcessorFlow[String, Int] = FlowFrom[String].map(_.hashCode)
      val open3: ProcessorFlow[Int, Int] = open1.append(open2)
      "open3.run()" shouldNot compile

      val closedInput: PublisherFlow[Int, Int] = open3.withInput(intSeq)
      "closedInput.run()" shouldNot compile

      val closedOutput: SubscriberFlow[Int, Int] = open3.withOutput(PublisherOut())
      "closedOutput.run()" shouldNot compile

      closedInput.withOutput(PublisherOut()).run()
      closedOutput.withInput(intSeq).run()
    }
    "prepend ProcessorFlow" in {
      val open1: ProcessorFlow[Int, String] = FlowFrom[Int].map(_.toString)
      val open2: ProcessorFlow[String, Int] = FlowFrom[String].map(_.hashCode)
      val open3: ProcessorFlow[String, String] = open1.prepend(open2)
      "open3.run()" shouldNot compile

      val closedInput: PublisherFlow[String, String] = open3.withInput(strSeq)
      "closedInput.run()" shouldNot compile

      val closedOutput: SubscriberFlow[String, String] = open3.withOutput(PublisherOut())
      "closedOutput.run()" shouldNot compile

      closedInput.withOutput(PublisherOut()).run
      closedOutput.withInput(strSeq).run
    }
    "append SubscriberFlow" in {
      val open: ProcessorFlow[Int, String] = FlowFrom[Int].map(_.toString)
      val closedOutput: SubscriberFlow[String, Int] = FlowFrom[String].map(_.hashCode).withOutput(PublisherOut())
      val appended: SubscriberFlow[Int, Int] = open.append(closedOutput)
      "appended.run()" shouldNot compile
      "appended.toFuture" shouldNot compile
      appended.withInput(intSeq).run
    }
    "prepend PublisherFlow" in {
      val open: ProcessorFlow[Int, String] = FlowFrom[Int].map(_.toString)
      val closedInput: PublisherFlow[String, Int] = FlowFrom[String].map(_.hashCode).withInput(strSeq)
      val prepended: PublisherFlow[String, String] = open.prepend(closedInput)
      "prepended.run()" shouldNot compile
      "prepended.withInput(strSeq)" shouldNot compile
      prepended.withOutput(PublisherOut()).run
    }
  }

  "SubscriberFlow" should {
    val openInput: SubscriberFlow[Int, String] =
      FlowFrom[Int].map(_.toString).withOutput(PublisherOut())
    "accept Input" in {
      openInput.withInput(intSeq)
    }
    "drop Output" in {
      openInput.withoutOutput
    }
    "not drop Input" in {
      "openInput.withoutInput" shouldNot compile
    }
    "not accept Output" in {
      "openInput.ToFuture" shouldNot compile
    }
    "not run()" in {
      "openInput.run()" shouldNot compile
    }
  }

  "PublisherFlow" should {
    val openOutput: PublisherFlow[Int, String] =
      FlowFrom(Seq(1, 2, 3)).map(_.toString)
    "accept Output" in {
      openOutput.withOutput(PublisherOut())
    }
    "drop Input" in {
      openOutput.withoutInput
    }
    "not drop Output" in {
      "openOutput.withoutOutput" shouldNot compile
    }
    "not accept Input" in {
      "openOutput.withInput(intSeq)" shouldNot compile
    }
    "not run()" in {
      "openOutput.run()" shouldNot compile
    }
  }

  "RunnableFlow" should {
    val closed: RunnableFlow[Int, String] =
      FlowFrom(Seq(1, 2, 3)).map(_.toString).withOutput(PublisherOut())
    "run" in {
      closed.run()
    }
    "drop Input" in {
      closed.withoutInput
    }
    "drop Output" in {
      closed.withoutOutput
    }
    "not accept Input" in {
      "closed.withInput(intSeq)" shouldNot compile
    }
    "not accept Output" in {
      "closed.ToFuture" shouldNot compile
    }
  }
}
