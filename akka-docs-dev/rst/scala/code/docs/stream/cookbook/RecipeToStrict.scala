package docs.stream.cookbook

import akka.stream.scaladsl.{ Sink, Source }

import scala.collection.immutable
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

class RecipeToStrict extends RecipeSpec {

  "Recipe for draining a stream into a strict collection" must {

    "work" in {
      val myData = Source(List("1", "2", "3"))
      val MaxAllowedSeqSize = 100

      //#draining-to-seq
      val strict: Future[immutable.Seq[Message]] =
        myData.grouped(MaxAllowedSeqSize).runWith(Sink.head)
      //#draining-to-seq

      Await.result(strict, 3.seconds) should be(List("1", "2", "3"))
    }

  }

}
