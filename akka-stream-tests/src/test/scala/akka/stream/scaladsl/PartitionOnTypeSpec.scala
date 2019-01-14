/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.stream.ActorMaterializer
import akka.stream.testkit.StreamSpec
import akka.stream.testkit.Utils.TE
import akka.testkit.{ DefaultTimeout, EventFilter }
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.Future
import scala.collection.immutable
import scala.util.{ Failure, Success, Try }

class PartitionOnTypeSpec extends StreamSpec(
  """akka.loggers = ["akka.testkit.TestEventListener"]""") with DefaultTimeout with ScalaFutures {

  implicit val mat = ActorMaterializer()

  sealed trait MyType
  case class Num(n: Int) extends MyType
  case class Txt(s: String) extends MyType
  object Surprise extends MyType

  "PartitionOnType" should {

    "partition on arbitrary type" in {

      val mySink =
        PartitionOnType[MyType]()
          .addSink[Num](Sink.seq)
          .addSink[Txt](Sink.seq)
          .build()

      val mats = Source(Txt("a") :: Num(1) :: Txt("b") :: Num(2) :: Nil)
        .runWith(mySink)

      mats(0).asInstanceOf[Future[immutable.Seq[Num]]].futureValue should ===(Num(1) :: Num(2) :: Nil)
      mats(1).asInstanceOf[Future[immutable.Seq[Txt]]].futureValue should ===(Txt("a") :: Txt("b") :: Nil)
    }

    "fail on unknown subtype" in {
      val mySink =
        PartitionOnType[MyType]()
          .addSink[Num](Sink.seq)
          .addSink[Txt](Sink.seq)
          .build()

      val expectedErrorText = "Element of type [akka.stream.scaladsl.PartitionOnTypeSpec$Surprise$] not covered by this PartitionOnType"
      EventFilter.error(expectedErrorText, occurrences = 1).intercept {
        val mats = Source(Txt("a") :: Num(1) :: Txt("b") :: Num(2) :: Surprise :: Nil)
          .runWith(mySink)

        // both these should be failed
        mats(0).asInstanceOf[Future[immutable.Seq[Num]]].failed.futureValue
        val failure = mats(1).asInstanceOf[Future[immutable.Seq[Txt]]].failed.futureValue

        failure.getMessage should ===(expectedErrorText)
      }
    }

    "provide prebuilt partition on try" in {
      val fail = TE("oh noes")
      val (successFuture, failureFuture) = Source[Try[Int]](Success(42) :: Failure(fail) :: Nil)
        .runWith(PartitionOnType.forTryMat(Sink.head[Int], Sink.head[Throwable])(Keep.both))

      successFuture.futureValue should ===(42)
      failureFuture.futureValue shouldBe theSameInstanceAs(fail)
    }

    "provide prebuilt partition on either" in {
      val (leftFuture, rightFuture) = Source[Either[String, Int]](Left("blue") :: Right(42) :: Nil)
        .runWith(PartitionOnType.forEitherMat(Sink.head[String], Sink.head[Int])(Keep.both))

      leftFuture.futureValue should ===("blue")
      rightFuture.futureValue should ===(42)
    }

  }

}
