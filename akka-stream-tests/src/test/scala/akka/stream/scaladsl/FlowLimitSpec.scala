package akka.stream.scaladsl

import akka.stream.testkit.Utils._
import akka.stream.{ StreamLimitReachedException, ActorMaterializer, ActorMaterializerSettings }
import akka.stream.testkit.AkkaSpec
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{ Failure, Try }

class FlowLimitSpec extends AkkaSpec {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val mat = ActorMaterializer(settings)

  "Limit" must {
    "produce output that is identical to the input when n = input.length" in {
      val input = (1 to 6)
      val n = input.length
      val future = Source(input).limit(n).grouped(Integer.MAX_VALUE).runWith(Sink.head)
      val result = Await.result(future, 300.millis)
      result should be(input.toSeq)
    }

    "produce output that is identical to the input when n > input.length" in {
      val input = (1 to 6)
      val n = input.length + 2 // n > input.length
      val future = Source(input).limit(n).grouped(Integer.MAX_VALUE).runWith(Sink.head)
      val result = Await.result(future, 300.millis)
      result should be(input.toSeq)
    }

    "produce n messages before throwing a StreamLimitReachedException when n < input.size" in {
      val input = (1 to 6)
      val n = input.length - 2 // n < input.length

      val future = Source(input).limit(n).grouped(Integer.MAX_VALUE).runWith(Sink.head)
      val result = Try(Await.result(future, 300.millis)) match {
        case Failure(t: StreamLimitReachedException) if t.n == n ⇒ true
        case _ ⇒ false
      }

      result should be(true)
    }

    "throw a StreamLimitReachedException when n < 0" in {
      val input = (1 to 6)
      val n = -1

      val future = Source(input).limit(n).grouped(Integer.MAX_VALUE).runWith(Sink.head)
      val result = Try(Await.result(future, 300.millis)) match {
        case Failure(t: StreamLimitReachedException) ⇒ true
        case _                                       ⇒ false
      }

      result should be(true)
    }
  }
}