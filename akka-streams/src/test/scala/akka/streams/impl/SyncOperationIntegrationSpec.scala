package akka.streams.impl

import scala.language.postfixOps

import org.scalatest.{ ShouldMatchers, FreeSpec }

import akka.streams._
import akka.pattern.Patterns.{ after ⇒ defer }
import Operation._
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom

class SyncOperationIntegrationSpec extends FreeSpec with ShouldMatchers with SyncOperationSpec {
  "Simple chains" - {
    "source + map" in pending
    "source + filter" in pending
    "source + flatten" in pending
    "source + take" in pending
    "source + takeWhile" in pending
    //FIXME Add them all
  }
  "Complex chains requiring back-forth chatter" - {
    "internal source + map + fold" in {
      expectTheFollowingStreamGiven(instance(Source(1 to 10).map(_ + 1).fold(0f)(_ + _.toFloat)))(65.0)
    }
    "flatten with generic producer" in pending
    "flatten.map(_ + 1f)" in {
      val impl = instance(Flatten[Float]().map(_ + 1f))
      pending
    }
    "mapAsync = flatMap with function returning future" in {
      import system.dispatcher
      // simulate a long-running complex addition
      def mapSlow(i: Int): Future[Int] =
        defer(ThreadLocalRandom.current().nextInt(0, 100).millis, system.scheduler, system.dispatcher, Future.successful(i + 1))

      val p = instance[Int](Source(1 to 6).flatMap(mapSlow))
      p.start().runToResult() should be(Continue)
      expectTheFollowingStreamGiven(p)(2, 3, 4, 5, 6, 7)
    }
    "span + flatten" in {
      expectTheFollowingStreamGiven(instance[Int](Source(1 to 6).span(_ % 3 == 0).flatten))(1, 2, 3, 4, 5, 6)
    }
    "map value to source and then flatten" in {
      def f(i: Int): Source[Int] = Source(999) ++ (1 to i)
      expectTheFollowingStreamGiven(
        instance[Int]((1 to 5 toSource).flatMap(f)))(999, 1, 999, 1, 2, 999, 1, 2, 3, 999, 1, 2, 3, 4, 999, 1, 2, 3, 4, 5)
    }
  }

  def instance[O](source: Source[O]): SyncSource =
    OperationImpl(downstream, TestContextEffects, source)
  def instance[I](operation: Operation[I, Float]): SyncOperation[I] =
    OperationImpl(upstream, downstream, TestContextEffects, operation)

  def expectTheFollowingStreamGiven[T](source: SyncSource)(items: T*) {
    val whatWeExpect = if (items.isEmpty) Vector(DownstreamComplete) else {
      val temp = items.map(DownstreamNext(_)).toVector
      temp.dropRight(1) :+ temp.last ~ DownstreamComplete
    }
    Vector.fill(whatWeExpect.size)(source.handleRequestMore(1).runToResult() match {
      case Continue ⇒ expectAndRunContextEffect().runToResult()
      case other    ⇒ other
    }) should be(whatWeExpect)
  }
}
