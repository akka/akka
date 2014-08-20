package akka.stream.dsl

import org.scalatest.{ WordSpec, Matchers }

import scala.collection.immutable.Seq

class GraphSpec extends WordSpec with Matchers {

  val intSeq = IterableIn(Seq(1, 2, 3))

  "Graph" should {
    "merge" in {
      val in1 = From[Int]
      val in2 = From[Int]
      val out1 = From[Int]
      val out2 = From[String]

      Graph().merge(in1, in2, out1)
      "Graph().merge(in1, in2, out2)" shouldNot compile
    }
    "zip" in {
      val in1 = From[Int]
      val in2 = From[String]
      val out1 = From[(Int, String)]
      val out2 = From[(String, Int)]

      Graph().zip(in1, in2, out1)
      "Graph().zip(in1, in2, out2)" shouldNot compile
    }
    "concat" in {
      val in1 = From[Int]
      val in2 = From[Int]
      val out1 = From[Int]
      val out2 = From[String]

      Graph().concat(in1, in2, out1)
      "Graph().concat(in1, in2, out2)" shouldNot compile
    }
    "broadcast" in {
      val in1 = From[Int].map(_ * 2)
      val in2 = From[Int].map(_.toString)
      val out1 = From[Int].map(_.toString)
      val out2 = From[Int].filter(_ % 2 == 0)

      Graph().broadcast(in1, Seq(out1, out2))
      "Graph().broadcast(in2, Seq(out1, out2))" shouldNot compile
    }
  }
}
