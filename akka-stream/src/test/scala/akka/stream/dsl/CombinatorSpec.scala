package akka.stream.dsl

import org.scalatest.Matchers
import org.scalatest.WordSpec
import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.Future
import akka.stream.OverflowStrategy

class CombinatorSpec extends WordSpec with Matchers {
  val f = From[Int]

  "Linear simple combinators in Flow" should {
    "map" in {
      val t: OpenFlow[Int, Int] = f.map(_ * 2)
    }
    "mapFuture" in {
      import scala.concurrent.ExecutionContext.Implicits.global
      val t: OpenFlow[Int, Int] = f.mapFuture(Future(_))
    }
    "filter" in {
      val t: OpenFlow[Int, Int] = f.filter(_ != 2)
    }
    "collect" in {
      val t: OpenFlow[Int, String] = f.collect {
        case i: Int if i == 2 ⇒ "two"
      }
    }
    "fold" in {
      val t: OpenFlow[Int, String] = f.fold("elements:") { (soFar, element) ⇒ soFar + element }
    }
    "drop" in {
      val t: OpenFlow[Int, Int] = f.drop(2)
    }
    "dropWithin" in {
      val t: OpenFlow[Int, Int] = f.dropWithin(2.seconds)
    }
    "take" in {
      val t: OpenFlow[Int, Int] = f.take(2)
    }
    "takeWithin" in {
      val t: OpenFlow[Int, Int] = f.takeWithin(2.seconds)
    }
    "grouped" in {
      val t: OpenFlow[Int, immutable.Seq[Int]] = f.grouped(2)
    }
    "groupedWithin" in {
      val t: OpenFlow[Int, immutable.Seq[Int]] = f.groupedWithin(2, 2.seconds)
    }
    "mapConcat" in {
      val t: OpenFlow[Int, Int] = f.mapConcat { i ⇒ immutable.Seq(i, i, i) }
    }
    "conflate" in {
      val t: OpenFlow[Int, String] = f.conflate(_.toString, (soFar: String, i) ⇒ soFar + i)
    }
    "expand" in {
      val t: OpenFlow[Int, String] = f.expand(_.toString, (soFar: String) ⇒ (soFar, "_"))
    }
    "buffer" in {
      val t: OpenFlow[Int, Int] = f.buffer(100, OverflowStrategy.DropHead)
    }
  }

  "Linear combinators which produce multiple flows" should {
    "prefixAndTail" in {
      val t: OpenFlow[Int, (immutable.Seq[String], OpenOutputFlow[String, String])] =
        f.map(_.toString).prefixAndTail(10)
    }
    "groupBy" in {
      val grouped: OpenOutputFlow[Int, (Int, OpenOutputFlow[Int, Int])] =
        From(immutable.Seq(1, 2, 3)).map(_ * 2).groupBy((o: Int) ⇒ o % 3)

      val closedInner: OpenOutputFlow[Int, (Int, ClosedFlow[Int, Int])] = grouped.map {
        case (key, openFlow) ⇒ (key, openFlow.withOutput(FutureOut()))
      }

      // both of these compile, even if `grouped` has inner flows unclosed
      grouped.withOutput(FutureOut()).run
      closedInner.withOutput(FutureOut()).run
    }
    "splitWhen" in {
      val t: OpenFlow[Int, OpenOutputFlow[String, String]] = f.map(_.toString).splitWhen(_.length > 2)
    }
  }

}