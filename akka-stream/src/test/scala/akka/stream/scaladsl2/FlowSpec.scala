/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import org.scalatest.{ Matchers, WordSpec }

import scala.collection.immutable.Seq
import scala.concurrent.Future

class FlowSpec extends WordSpec with Matchers {

  val intSeq = IterableIn(Seq(1, 2, 3))
  val strSeq = IterableIn(Seq("a", "b", "c"))

  import scala.concurrent.ExecutionContext.Implicits.global
  val intFut = FutureIn(Future { 3 })

  "OpenFlow" should {
    "go through all states" in {
      val f: OpenFlow[Int, Int] = From[Int]
        .withInput(intSeq)
        .withOutput(PublisherOut())
        .withoutInput
        .withoutOutput
    }
    "should not run" in {
      val open: OpenFlow[Int, Int] = From[Int]
      "open.run" shouldNot compile
    }
    "accept IterableIn" in {
      val f: OpenOutputFlow[Int, Int] = From[Int].withInput(intSeq)
    }
    "accept FutureIn" in {
      val f: OpenOutputFlow[Int, Int] = From[Int].withInput(intFut)
    }
    "append OpenFlow" in {
      val open1: OpenFlow[Int, String] = From[Int].map(_.toString)
      val open2: OpenFlow[String, Int] = From[String].map(_.hashCode)
      val open3: OpenFlow[Int, Int] = open1.append(open2)
      "open3.run" shouldNot compile

      val closedInput: OpenOutputFlow[Int, Int] = open3.withInput(intSeq)
      "closedInput.run" shouldNot compile

      val closedOutput: OpenInputFlow[Int, Int] = open3.withOutput(PublisherOut())
      "closedOutput.run" shouldNot compile

      closedInput.withOutput(PublisherOut()).run
      closedOutput.withInput(intSeq).run
    }
    "prepend OpenFlow" in {
      val open1: OpenFlow[Int, String] = From[Int].map(_.toString)
      val open2: OpenFlow[String, Int] = From[String].map(_.hashCode)
      val open3: OpenFlow[String, String] = open1.prepend(open2)
      "open3.run" shouldNot compile

      val closedInput: OpenOutputFlow[String, String] = open3.withInput(strSeq)
      "closedInput.run" shouldNot compile

      val closedOutput: OpenInputFlow[String, String] = open3.withOutput(PublisherOut())
      "closedOutput.run" shouldNot compile

      closedInput.withOutput(PublisherOut()).run
      closedOutput.withInput(strSeq).run
    }
    "append OpenInputFlow" in {
      val open: OpenFlow[Int, String] = From[Int].map(_.toString)
      val closedOutput: OpenInputFlow[String, Int] = From[String].map(_.hashCode).withOutput(PublisherOut())
      val appended: OpenInputFlow[Int, Int] = open.append(closedOutput)
      "appended.run" shouldNot compile
      "appended.toFuture" shouldNot compile
      appended.withInput(intSeq).run
    }
    "prepend OpenOutputFlow" in {
      val open: OpenFlow[Int, String] = From[Int].map(_.toString)
      val closedInput: OpenOutputFlow[String, Int] = From[String].map(_.hashCode).withInput(strSeq)
      val prepended: OpenOutputFlow[String, String] = open.prepend(closedInput)
      "prepended.run" shouldNot compile
      "prepended.withInput(strSeq)" shouldNot compile
      prepended.withOutput(PublisherOut()).run
    }
  }

  "OpenInputFlow" should {
    val openInput: OpenInputFlow[Int, String] =
      From[Int].map(_.toString).withOutput(PublisherOut())
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
    "not run" in {
      "openInput.run" shouldNot compile
    }
  }

  "OpenOutputFlow" should {
    val openOutput: OpenOutputFlow[Int, String] =
      From(Seq(1, 2, 3)).map(_.toString)
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
    "not run" in {
      "openOutput.run" shouldNot compile
    }
  }

  "ClosedFlow" should {
    val closed: ClosedFlow[Int, String] =
      From(Seq(1, 2, 3)).map(_.toString).withOutput(PublisherOut())
    "run" in {
      closed.run
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
