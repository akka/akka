/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.stream._
import akka.stream.testkit.TwoStreamsSetup
import com.github.ghik.silencer.silent
import org.scalacheck.Gen
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalacheck.Shrink

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

    commonTests()

  }
}
