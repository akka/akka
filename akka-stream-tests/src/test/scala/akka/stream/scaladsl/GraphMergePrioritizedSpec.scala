package akka.stream.scaladsl

import akka.NotUsed
import akka.stream._
import akka.stream.testkit.{ TestSubscriber, TwoStreamsSetup }

import scala.concurrent.Await
import scala.concurrent.duration._

class GraphMergePrioritizedSpec extends TwoStreamsSetup {
  import GraphDSL.Implicits._

  override type Outputs = Int

  override def fixture(b: GraphDSL.Builder[_]): Fixture = new Fixture(b) {
    val mergePrioritized = b add MergePrioritized[Outputs](Seq(2, 8))

    override def left: Inlet[Outputs] = mergePrioritized.in(0)
    override def right: Inlet[Outputs] = mergePrioritized.in(1)
    override def out: Outlet[Outputs] = mergePrioritized.out
  }

  "merge prioritized" must {
    commonTests()

    "stream data from all sources" in {
      val source1 = Source.fromIterator(() ⇒ (1 to 3).iterator)
      val source2 = Source.fromIterator(() ⇒ (4 to 6).iterator)
      val source3 = Source.fromIterator(() ⇒ (7 to 9).iterator)

      val priorities = Seq(6, 3, 1)

      val collected =
        threeSourceMergeSource(source1, source2, source3, priorities).runWith(Sink.collection)

      collected.futureValue.toSet should be(Set(1, 2, 3, 4, 5, 6, 7, 8, 9))
    }

    "stream data with priority" in {
      val source1 = Source.fromIterator(() ⇒ Iterator.continually(1))
      val source2 = Source.fromIterator(() ⇒ Iterator.continually(2))
      val source3 = Source.fromIterator(() ⇒ Iterator.continually(3))

      val priorities = Seq(6, 3, 1)

      val source =
        threeSourceMergeSource(source1, source2, source3, priorities)

      Slice.assert(source, 200.millis, maxTries = 20, target = 5) { collected ⇒
        val ones = collected.count(_ == 1).toDouble
        val twos = collected.count(_ == 2).toDouble
        val threes = collected.count(_ == 3).toDouble

        (ones / twos).round shouldEqual 2
        (ones / threes).round shouldEqual 6
        (twos / threes).round shouldEqual 3
      }
    }

    "stream data when only one source produces" in {
      val source1 = Source.fromIterator(() ⇒ Iterator.continually(1))
      val source2 = Source.fromIterator[Int](() ⇒ Iterator.empty)
      val source3 = Source.fromIterator[Int](() ⇒ Iterator.empty)

      val priorities = Seq(6, 3, 1)

      val source =
        threeSourceMergeSource(source1, source2, source3, priorities)

      Slice.assert(source, 200.millis, maxTries = 20, target = 5) { collected ⇒
        val ones = collected.count(_ == 1)
        val twos = collected.count(_ == 2)
        val threes = collected.count(_ == 3)

        ones should be > 0
        twos shouldEqual 0
        threes shouldEqual 0
      }
    }

    "stream data with priority when only two sources produce" in {
      val source1 = Source.fromIterator(() ⇒ Iterator.continually(1))
      val source2 = Source.fromIterator(() ⇒ Iterator.continually(2))
      val source3 = Source.fromIterator[Int](() ⇒ Iterator.empty)

      val priorities = Seq(6, 3, 1)

      val source =
        threeSourceMergeSource(source1, source2, source3, priorities)

      Slice.assert(source, 200.millis, maxTries = 20, target = 5) { collected ⇒
        val ones = collected.count(_ == 1).toDouble
        val twos = collected.count(_ == 2).toDouble
        val threes = collected.count(_ == 3)

        threes shouldEqual 0
        (ones / twos).round shouldBe 2
      }
    }
  }

  private def threeSourceMergeSource[T](source1: Source[T, NotUsed], source2: Source[T, NotUsed], source3: Source[T, NotUsed], priorities: Seq[Int]) = {
    Source.fromGraph(GraphDSL.create(source1, source2, source3)((_, _, _)) { implicit b ⇒ (s1, s2, s3) ⇒
      val merge = b.add(MergePrioritized[T](priorities))
      s1.out ~> merge.in(0)
      s2.out ~> merge.in(1)
      s3.out ~> merge.in(2)
      SourceShape(merge.out)
    })
  }
}

object Slice {
  final val Success = new Error("Success!")
  final class AssertionFailed(neededMore: Int, target: Int, maxTries: Int, val exceptions: Seq[Exception]) extends Exception(
    s"Was not able to satisfy the assertion at least [$target] times in [$maxTries] tries. [$neededMore] more successful assertions needed."
  )

  def assert[T](source: Source[T, _], duration: FiniteDuration, maxTries: Int, target: Int)(assertion: Seq[T] ⇒ Any)(implicit mat: Materializer): Unit = {
    val timeout = duration * (maxTries + 2)

    val result = source
      // introduce an async boundary on the consuming side making it more likely that
      // the actual prioritization happens and elements does not just pass through
      .async
      .groupedWithin(Int.MaxValue, duration)
      .runFold((maxTries, target, Seq.empty[Exception])) {
        // no more tries left
        case ((0, more, exceptions), _) ⇒ throw new AssertionFailed(more, target, maxTries, exceptions)

        // reached the target successful assertions
        case ((_, 0, _), _)             ⇒ throw Success

        case ((max, success, exceptions), collected) ⇒ try {
          assertion(collected)
          (max - 1, success - 1, exceptions)
        } catch {
          case ex: Exception ⇒
            (max - 1, success, ex +: exceptions)
        }
      }

    Await.result(result.failed, timeout) match {
      case ex if ex.getCause != null && ex.getCause == Success ⇒
      case ex: AssertionFailed ⇒
        ex.exceptions.foreach(_.printStackTrace)
        throw ex
      case ex ⇒ throw ex
    }
  }
}
