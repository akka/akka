package akka.stream.dsl

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
      val f = From[Int]
        .withInput(intSeq)
        .withOutput(FutureOut())
        .withoutInput
        .withoutOutput
    }
    "should not run" in {
      val open = From[Int]
      "open.run" shouldNot compile
    }
    "accept IterableIn" in {
      val f = From[Int].withInput(intSeq)
    }
    "accept FutureIn" in {
      val f = From[Int].withInput(intFut)
    }
    "append OpenFlow" in {
      val open1 = From[Int].map(_.toString)
      val open2 = From[String].map(_.hashCode)
      val open3 = open1.append(open2)
      "open3.run" shouldNot compile

      val closedInput = open3.withInput(intSeq)
      "closedInput.run" shouldNot compile

      val closedOutput = open3.ToFuture
      "closedOutput.run" shouldNot compile

      closedInput.ToFuture.run
      closedOutput.withInput(intSeq).run
    }
    "prepend OpenFlow" in {
      val open1 = From[Int].map(_.toString)
      val open2 = From[String].map(_.hashCode)
      val open3 = open1.prepend(open2)
      "open3.run" shouldNot compile

      val closedInput = open3.withInput(strSeq)
      "closedInput.run" shouldNot compile

      val closedOutput = open3.ToFuture
      "closedOutput.run" shouldNot compile

      closedInput.ToFuture.run
      closedOutput.withInput(strSeq).run
    }
    "append OpenInputFlow" in {
      val open = From[Int].map(_.toString)
      val closed = From[String].map(_.hashCode).ToFuture
      val appended = open.appendClosed(closed)
      "appended.run" shouldNot compile
      "appended.toFuture" shouldNot compile
      appended.withInput(intSeq).run
    }
    "prepend OpenOutputFlow" in {
      val open = From[Int].map(_.toString)
      val closed = From[String].map(_.hashCode).withInput(strSeq)
      val appended = open.prependClosed(closed)
      "appended.run" shouldNot compile
      "appended.withInput(strSeq)" shouldNot compile
      appended.ToFuture.run
    }
    "groupBy" in {
      val grouped = From(Seq(1, 2, 3)).map(_ * 2).groupBy((o: Int) ⇒ o % 3)

      val closedInner = grouped.map {
        case (key, openFlow) ⇒ (key, openFlow.ToFuture)
      }

      // both of these compile, even if `grouped` has inner flows unclosed
      grouped.ToFuture.run
      closedInner.ToFuture.run
    }
  }

  "OpenInputFlow" should {
    val openInput = From[Int].map(_.toString).ToFuture
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
    val openOutput = From(Seq(1, 2, 3)).map(_.toString)
    "accept Output" in {
      openOutput.ToFuture
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
    val closed = From(Seq(1, 2, 3)).map(_.toString).ToFuture
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
