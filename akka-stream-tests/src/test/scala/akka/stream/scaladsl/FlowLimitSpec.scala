package akka.stream.scaladsl

import akka.stream.testkit.Utils._
import akka.stream.{ StreamLimitReachedException, ActorMaterializer, ActorMaterializerSettings }
import akka.stream.testkit.AkkaSpec
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{ Failure, Try }

class FlowLimitSpec extends AkkaSpec {
  import system.dispatcher

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val mat = ActorMaterializer(settings)

  // Source(size = n).limit(n) == ???
  "Limit" must {
    "produce output that is identical to the input when n = input.length" in assertAllStagesStopped {
      val input = (1 to 6)
      val n = input.length
      val future = Source(input).limit(n).grouped(Integer.MAX_VALUE).runWith(Sink.head)
      val result = Await.result(future, 300.millis)
      result == input.toSeq
    }
  }

  "produce output that is identical to the input when n > input.length" in assertAllStagesStopped {
    val input = (1 to 6)
    val n = input.length + 2 // n > input.length
    val future = Source(input).limit(n).grouped(Integer.MAX_VALUE).runWith(Sink.head)
    val result = Await.result(future, 300.millis)
    result == input.toSeq
  }

  "produce n messages before throwing a StreamLimitReachedException when n < input.size" in {
    val input = (1 to 6)
    val n = input.length - 2 // n < input.length
    val future = Source(input).limit(n).grouped(Integer.MAX_VALUE).runWith(Sink.head)
    val result = Try(Await.result(future, 300.millis))
    result match {
      case Failure(t: StreamLimitReachedException) if t.n == n ⇒ true
      case _ ⇒ false
    }
  }

  // Source(size = m).limit(n) where m < n == ???
  // Source(size = m).limit(n) where m > n == ???

  // Source(size = n).limit(n) where one of the element throws an exception
}