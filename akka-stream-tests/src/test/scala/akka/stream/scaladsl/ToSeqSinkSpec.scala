package akka.stream.scaladsl

import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import akka.stream.testkit.AkkaSpec
import scala.concurrent.Await
import scala.concurrent.duration._

class ToSeqSinkSpec extends AkkaSpec {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val mat = ActorMaterializer(settings)

  "Sink.toSeq" must {
    "return a Seq[T] from a Source" in {
      val input = (1 to 6)
      val future = Source(input).runWith(Sink.toSeq)
      val result = Await.result(future, 300.millis)
      input should be(input.toSeq)
    }
  }
}
