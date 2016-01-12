/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.testkit.Utils._
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import akka.stream.testkit._
import org.scalacheck.Gen
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import akka.pattern.pipe

import scala.concurrent.Await

class FlowSlidingSpec extends AkkaSpec with GeneratorDrivenPropertyChecks {
  import system.dispatcher
  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val materializer = ActorMaterializer(settings)

  "Sliding" must {
    import org.scalacheck.Shrink.shrinkAny
    def check(gen: Gen[(Int, Int, Int)]): Unit =
      forAll(gen, MinSize(1000), MaxSize(1000)) {
        case (len, win, step) ⇒
          val af = Source.fromIterator(() ⇒ Iterator.from(0).take(len)).sliding(win, step).runFold(Seq.empty[Seq[Int]])(_ :+ _)
          val cf = Source.fromIterator(() ⇒ Iterator.from(0).take(len).sliding(win, step)).runFold(Seq.empty[Seq[Int]])(_ :+ _)
          Await.result(af, remaining) should be(Await.result(cf, remaining))
      }

    "behave just like collections sliding with step < window" in assertAllStagesStopped {
      check(for {
        len ← Gen.choose(0, 31)
        win ← Gen.choose(1, 61)
        step ← Gen.choose(1, win - 1)
      } yield (len, win, step))
    }

    "behave just like collections sliding with step == window" in assertAllStagesStopped {
      check(for {
        len ← Gen.choose(0, 31)
        win ← Gen.choose(1, 61)
        step ← Gen.const(win)
      } yield (len, win, step))
    }

    "behave just like collections sliding with step > window" in assertAllStagesStopped {
      check(for {
        len ← Gen.choose(0, 31)
        win ← Gen.choose(1, 61)
        step ← Gen.choose(win + 1, 127)
      } yield (len, win, step))
    }

    "work with empty sources" in assertAllStagesStopped {
      Source.empty.sliding(1).runForeach(testActor ! _).map(_ ⇒ "done") pipeTo testActor
      expectMsg("done")
    }
  }
}
