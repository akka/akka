/**
 *  Copyright (C) 2015 Typesafe <http://typesafe.com/>
 */
package docs.stream.cookbook

import akka.stream.scaladsl.{ Sink, Source }

import scala.collection.immutable
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

class RecipeSeq extends RecipeSpec {

  "Recipe for draining a stream into a strict collection" must {

    "work" in {
      val result = immutable.Seq[Message]("1", "2", "3")
      val myData = Source(result)
      val MaxAllowedSeqSize = 100

      //#draining-to-seq-unsafe
      val unsafe: Future[Seq[Message]] = myData.runWith(Sink.seq) // dangerous!
      //#draining-to-seq-unsafe

      Await.result(unsafe, 3.seconds) should be(result)
    }

    "work together with limit(n)" in {
      val result = List("1", "2", "3")
      val myData = Source(result)
      val MaxAllowedSeqSize = 100

      //#draining-to-seq-safe
      // OK. Future will fail with a `StreamLimitReachedException`
      // if the number of incoming elements is larger than MaxAllowedSeqSize
      val safe1: Future[immutable.Seq[Message]] = myData.limit(MaxAllowedSeqSize).runWith(Sink.seq)
      //#draining-to-seq-safe

      Await.result(safe1, 3.seconds) should be(result)
    }

    "work together with take(n)" in {
      val result = List("1", "2", "3")
      val myData = Source(result)
      val MaxAllowedSeqSize = 100

      //#draining-to-seq-safe
      // OK. Collect up until MaxAllowedSeqSize-th elements only, then cancel upstream
      val safe2: Future[immutable.Seq[Message]] = myData.take(MaxAllowedSeqSize).runWith(Sink.seq)
      //#draining-to-seq-safe

      Await.result(safe2, 3.seconds) should be(result)
    }
  }

}
