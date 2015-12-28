package docs.stream.cookbook

import akka.stream.scaladsl.{ Sink, Source }

import scala.collection.immutable
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

class RecipeSeq extends RecipeSpec {

  "Recipe for draining a stream into a strict collection" must {

    "work" in {
      val myData = Source(List("1", "2", "3"))
      val MaxAllowedSeqSize = 100

      //#draining-to-seq-unsafe
      val strictUnsafe: Future[immutable.Seq[Message]] = myData.runWith(Sink.seq) // dangerous! if myData is unbounded, will cause OutOfMemory exception
      //#draining-to-seq-unsafe

      Await.result(strictUnsafe, 3.seconds) should be(List("1", "2", "3"))
    }

    "work together with limit(n)" in {
      val myData = Source(List("1", "2", "3"))
      val MaxAllowedSeqSize = 100

      //#draining-to-seq-limit
      val strictSafe1: Future[immutable.Seq[Message]] = myData.limit(MaxAllowedSeqSize).runWith(Sink.seq) // ok. Future will fail with a `StreamLimitReachedException` if the number of incoming elements is larger than MaxAllowedSeqSize
      //#draining-to-seq-limit

      Await.result(strictSafe1, 3.seconds) should be(List("1", "2", "3"))
    }

    "work together with take(n)" in {
      val myData = Source(List("1", "2", "3"))
      val MaxAllowedSeqSize = 100

      //#draining-to-seq-take
      val strictSafe2: Future[immutable.Seq[Message]] = myData.take(MaxAllowedSeqSize).runWith(Sink.seq) // ok. Collect up until `MaxAllowedSeqSize`-th elements only (drop the rest if any)
      //#draining-to-seq-take

      Await.result(strictSafe2, 3.seconds) should be(List("1", "2", "3"))
    }
  }

}
