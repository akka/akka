package docs.stream.cookbook

import akka.stream.OverflowStrategy
import akka.stream.scaladsl._

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

class RecipeReduceByKey extends RecipeSpec {

  "Reduce by key recipe" must {

    val MaximumDistinctWords = 1000

    "work with simple word count" in {

      def words = Source(List("hello", "world", "and", "hello", "universe", "akka") ++ List.fill(1000)("rocks!"))

      //#word-count
      // split the words into separate streams first
      val wordStreams: Source[(String, Source[String, Unit]), Unit] = words.groupBy(identity)

      // add counting logic to the streams
      val countedWords: Source[Future[(String, Int)], Unit] = wordStreams.map {
        case (word, wordStream) =>
          wordStream.runFold((word, 0)) {
            case ((w, count), _) => (w, count + 1)
          }
      }

      // get a stream of word counts
      val counts: Source[(String, Int), Unit] =
        countedWords
          .buffer(MaximumDistinctWords, OverflowStrategy.fail)
          .mapAsync(4)(identity)
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

        val groupStreams = Flow[In].groupBy(groupKey)
        val reducedValues = groupStreams.map {
          case (key, groupStream) =>
            groupStream.runFold((key, foldZero(key))) {
              case ((key, aggregated), elem) => (key, fold(aggregated, elem))
            }
        }

        reducedValues.buffer(maximumGroupSize, OverflowStrategy.fail).mapAsync(4)(identity)
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
