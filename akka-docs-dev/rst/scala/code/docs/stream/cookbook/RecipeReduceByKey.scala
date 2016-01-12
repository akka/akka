package docs.stream.cookbook

import akka.stream.{ Graph, FlowShape, Inlet, Outlet, Attributes, OverflowStrategy }
import akka.stream.scaladsl._
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import akka.stream.stage.{ GraphStage, GraphStageLogic }

class RecipeReduceByKey extends RecipeSpec {

  "Reduce by key recipe" must {

    val MaximumDistinctWords = 1000

    "work with simple word count" in {

      def words = Source(List("hello", "world", "and", "hello", "universe", "akka") ++ List.fill(1000)("rocks!"))

      //#word-count
      val counts: Source[(String, Int), Unit] = words
        // split the words into separate streams first
        .groupBy(MaximumDistinctWords, identity)
        // add counting logic to the streams
        .fold(("", 0)) {
          case ((_, count), word) => (word, count + 1)
        }
        // get a stream of word counts
        .mergeSubstreams
      //#word-count

      Await.result(counts.grouped(10).runWith(Sink.head), 3.seconds).toSet should be(Set(
        ("hello", 2),
        ("world", 1),
        ("and", 1),
        ("universe", 1),
        ("akka", 1),
        ("rocks!", 1000)))
    }

    "work generalized" in {

      def words = Source(List("hello", "world", "and", "hello", "universe", "akka") ++ List.fill(1000)("rocks!"))

      //#reduce-by-key-general
      def reduceByKey[In, K, Out](
        maximumGroupSize: Int,
        groupKey: (In) => K,
        foldZero: (K) => Out)(fold: (Out, In) => Out): Flow[In, (K, Out), Unit] = {

        Flow[In]
          .groupBy(maximumGroupSize, groupKey)
          .fold(Option.empty[(K, Out)]) {
            case (None, elem) =>
              val key = groupKey(elem)
              Some((key, fold(foldZero(key), elem)))
            case (Some((key, out)), elem) =>
              Some((key, fold(out, elem)))
          }
          .map(_.get)
          .mergeSubstreams
      }

      val wordCounts = words.via(reduceByKey(
        MaximumDistinctWords,
        groupKey = (word: String) => word,
        foldZero = (key: String) => 0)(fold = (count: Int, elem: String) => count + 1))

      //#reduce-by-key-general

      Await.result(wordCounts.grouped(10).runWith(Sink.head), 3.seconds).toSet should be(Set(
        ("hello", 2),
        ("world", 1),
        ("and", 1),
        ("universe", 1),
        ("akka", 1),
        ("rocks!", 1000)))

    }
  }

}
