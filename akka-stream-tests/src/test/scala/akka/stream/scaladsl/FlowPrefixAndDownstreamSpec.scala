/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.stream.testkit.StreamSpec
import akka.stream.testkit.scaladsl.StreamTestKit.assertAllStagesStopped
//import org.scalatest.concurrent.PatienceConfiguration.Interval
//import org.scalatest.time.{Minutes, Span}

class FlowPrefixAndDownstreamSpec extends StreamSpec {
  //import system.dispatcher

  def src10(i: Int = 0) = Source(i until (i + 10))

  "A PrefixAndDownstream" must {

    "work in the simple identity case" in assertAllStagesStopped {
      src10()
        .prefixAndDownstreamMat(2){ _ =>
          println("materializing flow")
          Flow[Int]
        }(Keep.left)
        .alsoTo(Sink.foreach(println(_)))
        .runWith(Sink.seq[Int])
        .futureValue/*(Interval(Span(1000, Minutes)))*/ should === (2 until 10)
    }

    "expose mat value in the simple identity case" in assertAllStagesStopped {
      val (prefixF, suffixF) = src10()
        .prefixAndDownstreamMat(2){ prefix =>
          Flow[Int].mapMaterializedValue(_ => prefix)
        } (Keep.right)
        .toMat(Sink.seq) (Keep.both)
        .run

      prefixF.futureValue should === (0 until 2)
      suffixF.futureValue/*(Interval(Span(1000, Minutes)))*/ should === (2 until 10)
    }

    "work when source is exactly the required prefix" in assertAllStagesStopped {
      val (prefixF, suffixF) = src10()
        .prefixAndDownstreamMat(10){ prefix =>
          Flow[Int].mapMaterializedValue(_ => prefix)
        } (Keep.right)
        .toMat(Sink.seq) (Keep.both)
        .run

      prefixF.futureValue should === (0 until 10)
      suffixF.futureValue should be (empty)
    }

    "work when source has less than the required prefix" in assertAllStagesStopped {
      val (prefixF, suffixF) = src10()
        .prefixAndDownstreamMat(20){ prefix =>
          Flow[Int].mapMaterializedValue(_ => prefix)
        } (Keep.right)
        .toMat(Sink.seq) (Keep.both)
        .run

      prefixF.futureValue should === (0 until 10)
      suffixF.futureValue/*(Interval(Span(1000, Minutes)))*/ should be (empty)
    }

    "simple identity case when downstream completes before consuming the entire stream" in assertAllStagesStopped {
      val (prefixF, suffixF) = Source(0 until 100)
        .prefixAndDownstreamMat(10){ prefix =>
          Flow[Int].mapMaterializedValue(_ => prefix)
        } (Keep.right)
        .take(10)
        .toMat(Sink.seq) (Keep.both)
        .run

      prefixF.futureValue should === (0 until 10)
      suffixF.futureValue/*(Interval(Span(1000, Minutes)))*/ should === (10 until 20)
    }

    "propagate materialization failure" in assertAllStagesStopped {
      val suffixF = Source(0 until 100)
        .prefixAndDownstreamMat(10){ prefix =>
          sys error s"I hate mondays! (${prefix.size})"
        } (Keep.right)
        .to(Sink.ignore)
        .run

      val ex = suffixF.failed.futureValue
      ex.getCause should not be null
      ex.getCause should have message ("I hate mondays! (10)")
    }

    "propagate flow failures" in assertAllStagesStopped {
      val (prefixF, suffixF) = Source(0 until 100)
        .prefixAndDownstreamMat(10){ prefix =>
          Flow[Int]
            .mapMaterializedValue(_ => prefix)
            .map{
              case 15 => sys error "don't like 15 either!"
              case n => n
            }
        } (Keep.right)
        .toMat(Sink.ignore)(Keep.both)
        .run
      prefixF.futureValue should === (0 until 10)
      val ex = suffixF.failed.futureValue
      //ex.printStackTrace()
      ex should have message ("don't like 15 either!")
    }

    "produce multiple elements per input" in assertAllStagesStopped {
      val(prefixF, suffixF) =  src10()
        .prefixAndDownstreamMat(7){ prefix =>
          Flow[Int]
            .mapMaterializedValue(_ => prefix)
            .mapConcat(n => List.fill(n - 6)(n))
        }(Keep.right)
        .toMat(Sink.seq[Int])(Keep.both)
        .run()

      prefixF.futureValue should === (0 until 7)
      suffixF.futureValue should === (7 :: 8 :: 8 :: 9 :: 9 :: 9 :: Nil)
    }

    "succeed when upstream produces no elements" in assertAllStagesStopped {
      val(prefixF, suffixF) =  Source.empty[Int]
        .prefixAndDownstreamMat(7){ prefix =>
          Flow[Int]
            .mapMaterializedValue(_ => prefix)
            .mapConcat(n => List.fill(n - 6)(n))
        }(Keep.right)
        .toMat(Sink.seq[Int])(Keep.both)
        .run()

      prefixF.futureValue should be(empty)
      suffixF.futureValue should be(empty)
    }

    "apply materialized flow's semantics when upstream produces no elements" in assertAllStagesStopped {
      val(prefixF, suffixF) =  Source.empty[Int]
        .prefixAndDownstreamMat(7){ prefix =>
          Flow[Int]
            .mapMaterializedValue(_ => prefix)
            .mapConcat(n => List.fill(n - 6)(n))
            .prepend(Source(100 to 101))
        }(Keep.right)
        .toMat(Sink.seq[Int])(Keep.both)
        .run()

      prefixF.futureValue should be(empty)
      suffixF.futureValue should ===(100 :: 101 :: Nil)
    }
  }

}
