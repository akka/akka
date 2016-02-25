/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.stream.{ StreamLimitReachedException, ActorMaterializer, ActorMaterializerSettings }
import akka.testkit.AkkaSpec
import scala.concurrent.Await
import scala.concurrent.duration._

class FlowLimitWeightedSpec extends AkkaSpec {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val mat = ActorMaterializer(settings)

  "Limit" must {
    "produce empty sequence regardless of cost when source is empty and n = 0" in {
      val input = Range(0, 0, 1)
      val n = input.length
      def costFn(e: Int): Long = 999999L // set to an arbitrarily big value
      val future = Source(input).limitWeighted(n)(costFn).grouped(Integer.MAX_VALUE).runWith(Sink.headOption)
      val result = Await.result(future, 300.millis)
      result should be(None)
    }

    "always exhaust a source regardless of n (as long as n > 0) if cost is 0" in {
      val input = (1 to 15)
      def costFn(e: Int): Long = 0L
      val n = 1 // must not matter since costFn always evaluates to 0
      val future = Source(input).limitWeighted(n)(costFn).grouped(Integer.MAX_VALUE).runWith(Sink.head)
      val result = Await.result(future, 300.millis)
      result should be(input.toSeq)
    }

    "exhaust source if n equals to input length and cost is 1" in {
      val input = (1 to 16)
      def costFn(e: Int): Long = 1L
      val n = input.length
      val future = Source(input).limitWeighted(n)(costFn).grouped(Integer.MAX_VALUE).runWith(Sink.head)
      val result = Await.result(future, 300.millis)
      result should be(input.toSeq)
    }

    "exhaust a source if n >= accumulated cost" in {
      val input = List("this", "is", "some", "string")
      def costFn(e: String): Long = e.length
      val n = input.flatten.length
      val future = Source(input).limitWeighted(n)(costFn).grouped(Integer.MAX_VALUE).runWith(Sink.head)
      val result = Await.result(future, 300.millis)
      result should be(input.toSeq)
    }

    "throw a StreamLimitReachedException when n < accumulated cost" in {
      val input = List("this", "is", "some", "string")
      def costFn(e: String): Long = e.length
      val n = input.flatten.length - 1
      val future = Source(input).limitWeighted(n)(costFn).grouped(Integer.MAX_VALUE).runWith(Sink.head)

      a[StreamLimitReachedException] shouldBe thrownBy {
        Await.result(future, 300.millis)
      }
    }
  }
}