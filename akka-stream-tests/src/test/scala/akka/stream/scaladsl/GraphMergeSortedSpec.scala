/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.NotUsed
import akka.stream._
import akka.stream.testkit.TwoStreamsSetup
import com.github.ghik.silencer.silent
import org.scalacheck.{Gen, Shrink}
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalacheck.Shrink

import scala.collection.immutable

@silent // tests deprecated apis
class GraphMergeSortedSpec extends TwoStreamsSetup with GeneratorDrivenPropertyChecks {

  override type Outputs = Int

  override def fixture(b: GraphDSL.Builder[_]): Fixture = new Fixture {
    val merge = b.add(new MergeSorted[Outputs])

    override def left: Inlet[Outputs] = merge.in0
    override def right: Inlet[Outputs] = merge.in1
    override def out: Outlet[Outputs] = merge.out
  }

  implicit def noShrink[T] = Shrink[T](_ => Stream.empty) // do not shrink failures, it only destroys evidence

  "MergeSorted" must {

    "work in the nominal case" in {
      val gen = Gen.listOf(Gen.oneOf(false, true))

      forAll(gen) { picks =>
        val N = picks.size
        val (left, right) = picks.zipWithIndex.partition(_._1)
        Source(left.map(_._2))
          .mergeSorted(Source(right.map(_._2)))
          .grouped(N max 1)
          .concat(Source.single(Nil))
          .runWith(Sink.head)
          .futureValue should ===(0 until N)
      }
    }

    "work as a special case of MergeSortedN with N=2" in {
      val gen = Gen.listOf(Gen.oneOf(false, true))

      forAll(gen) { picks ⇒
        val N = picks.size
        val (left, right) = picks.zipWithIndex.partition(_._1)
        Source.mergeSortedN(List(Source(left.map(_._2)), Source(right.map(_._2))))
          .grouped(N max 1)
          .concat(Source.single(Nil))
          .runWith(Sink.head)
          .futureValue should ===(0 until N)
      }
    }

    "work" in {
      // List(false, false, false, true, false, true, false, true, true, false)
      val leftSource: Source[Int, NotUsed] = Source(List(0, 2))
      val rightSource: Source[Int, NotUsed] = Source(List(1))


      val underTest: Source[Int, NotUsed] = Source.mergeSortedN(List(leftSource, rightSource))

      val grouped: Source[immutable.Seq[Int], NotUsed] = underTest
        .grouped(4)

      val sortedResult: immutable.Seq[Int] = grouped
        .concat(Source.single(Nil))
        .runWith(Sink.head)
        .futureValue


      sortedResult shouldBe List(0,1,2)
    }

    commonTests()

  }
}
