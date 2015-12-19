package akka.stream.scaladsl

import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import akka.stream.testkit.AkkaSpec
import scala.concurrent.Await
import scala.concurrent.duration._

class SeqSinkSpec extends AkkaSpec {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val mat = ActorMaterializer(settings)

  "Sink.toSeq" must {
    "return a Seq[T] from a Source" in {
      val input = (1 to 6)
      val future = Source(input).runWith(Sink.seq)
      val result = Await.result(future, 300.millis)
      result should be(input.toSeq)
    }

    "return an empty Seq[T] from an empty Source" in {
      val input: Seq[Int] = Seq.empty
      val future = Source.fromIterator(() ⇒ input.iterator).runWith(Sink.seq)
      val result = Await.result(future, 300.millis)
      result should be(Seq.empty: Seq[Int])
    }
  }
}
