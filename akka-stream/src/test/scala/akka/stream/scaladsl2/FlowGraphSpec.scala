/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import org.scalatest.{ WordSpec, Matchers }

import scala.collection.immutable.Seq

class GraphSpec extends WordSpec with Matchers {

  "A Flow Graph" should {
    "merge" in {
      val merge = Merge[Int, Int, Int]()

      val in1 = From[Int].withOutput(merge.in1)
      val in2 = From[Int].withOutput(merge.in2)
      val out1 = From[Int].withInput(merge.out)

      val out2 = From[String]
      // FIXME: make me not compile
      //"out2.withInput(merge.out)" shouldNot compile
    }
    "zip" in {
      val zip = Zip[Int, String]()

      val in1 = From[Int].withOutput(zip.in1)
      val in2 = From[String].withOutput(zip.in2)
      val out1 = From[(Int, String)].withInput(zip.out)

      val out2 = From[(String, Int)]
      // FIXME: make me not compile
      //"out2.withInput(zip.out)" shouldNot compile
    }
    "concat" in {
      trait A
      trait B extends A

      val concat = Concat[A, B, A]()
      val in1 = From[A].withOutput(concat.in1)
      val in2 = From[B].withOutput(concat.in2)
      val out1 = From[A].withInput(concat.out)

      val out2 = From[String]
      // FIXME: make me not compile
      //"out2.withInput(concat.out)" shouldNot compile
    }
    "broadcast" in {
      val broadcast = Broadcast[Int]()

      val in1 = From[Int].withOutput(broadcast.in)
      val in2 = From[Int].withInput(broadcast.out1)
      val out1 = From[Int].withInput(broadcast.out2)

      val out2 = From[String]
      // FIXME: make me not compile
      //"out2.withInput(broadcast.out2)" shouldNot compile
    }
  }
}
