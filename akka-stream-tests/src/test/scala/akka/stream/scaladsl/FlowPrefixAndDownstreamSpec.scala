/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.stream.{AbruptTerminationException, Materializer, NeverMaterializedException, SubscriptionWithCancelException}
import akka.{Done, NotUsed}
import akka.stream.testkit.Utils.TE
import akka.stream.testkit.{StreamSpec, TestPublisher, TestSubscriber}
import akka.stream.testkit.scaladsl.StreamTestKit.assertAllStagesStopped

//import scala.concurrent.Future
//import org.scalatest.concurrent.PatienceConfiguration.Interval
//import org.scalatest.time.{Minutes, Span}

class FlowPrefixAndDownstreamSpec extends StreamSpec {
  //import system.dispatcher

  def src10(i: Int = 0) = Source(i until (i + 10))

  "A PrefixAndDownstream" must {

    "work in the simple identity case" in assertAllStagesStopped {
      src10()
        .prefixAndDownstreamMat(2) { _ =>
          println("materializing flow")
          Flow[Int]
        }(Keep.left)
        .alsoTo(Sink.foreach(println(_)))
        .runWith(Sink.seq[Int])
        .futureValue /*(Interval(Span(1000, Minutes)))*/ should ===(2 until 10)
    }

    "expose mat value in the simple identity case" in assertAllStagesStopped {
      val (prefixF, suffixF) = src10()
        .prefixAndDownstreamMat(2) { prefix =>
          Flow[Int].mapMaterializedValue(_ => prefix)
        }(Keep.right)
        .toMat(Sink.seq)(Keep.both)
        .run

      prefixF.futureValue should ===(0 until 2)
      suffixF.futureValue /*(Interval(Span(1000, Minutes)))*/ should ===(2 until 10)
    }

    "work when source is exactly the required prefix" in assertAllStagesStopped {
      val (prefixF, suffixF) = src10()
        .prefixAndDownstreamMat(10) { prefix =>
          Flow[Int].mapMaterializedValue(_ => prefix)
        }(Keep.right)
        .toMat(Sink.seq)(Keep.both)
        .run

      prefixF.futureValue should ===(0 until 10)
      suffixF.futureValue should be(empty)
    }

    "work when source has less than the required prefix" in assertAllStagesStopped {
      val (prefixF, suffixF) = src10()
        .prefixAndDownstreamMat(20) { prefix =>
          Flow[Int].mapMaterializedValue(_ => prefix)
        }(Keep.right)
        .toMat(Sink.seq)(Keep.both)
        .run

      prefixF.futureValue should ===(0 until 10)
      suffixF.futureValue /*(Interval(Span(1000, Minutes)))*/ should be(empty)
    }

    "simple identity case when downstream completes before consuming the entire stream" in assertAllStagesStopped {
      val (prefixF, suffixF) = Source(0 until 100)
        .prefixAndDownstreamMat(10) { prefix =>
          Flow[Int].mapMaterializedValue(_ => prefix)
        }(Keep.right)
        .take(10)
        .toMat(Sink.seq)(Keep.both)
        .run

      prefixF.futureValue should ===(0 until 10)
      suffixF.futureValue /*(Interval(Span(1000, Minutes)))*/ should ===(10 until 20)
    }

    "propagate failure to create the downstream flow" in assertAllStagesStopped {
      val suffixF = Source(0 until 100)
        .prefixAndDownstreamMat(10) { prefix =>
          throw TE(s"I hate mondays! (${prefix.size})")
        }(Keep.right)
        .to(Sink.ignore)
        .run

      val ex = suffixF.failed.futureValue
      ex.getCause should not be null
      ex.getCause should ===(TE("I hate mondays! (10)"))
    }

    "propagate flow failures" in assertAllStagesStopped {
      val (prefixF, suffixF) = Source(0 until 100)
        .prefixAndDownstreamMat(10) { prefix =>
          Flow[Int].mapMaterializedValue(_ => prefix).map {
            case 15 => throw TE("don't like 15 either!")
            case n  => n
          }
        }(Keep.right)
        .toMat(Sink.ignore)(Keep.both)
        .run
      prefixF.futureValue should ===(0 until 10)
      val ex = suffixF.failed.futureValue
      //ex.printStackTrace()
      ex should ===(TE("don't like 15 either!"))
    }

    "produce multiple elements per input" in assertAllStagesStopped {
      val (prefixF, suffixF) = src10()
        .prefixAndDownstreamMat(7) { prefix =>
          Flow[Int].mapMaterializedValue(_ => prefix).mapConcat(n => List.fill(n - 6)(n))
        }(Keep.right)
        .toMat(Sink.seq[Int])(Keep.both)
        .run()

      prefixF.futureValue should ===(0 until 7)
      suffixF.futureValue should ===(7 :: 8 :: 8 :: 9 :: 9 :: 9 :: Nil)
    }

    "succeed when upstream produces no elements" in assertAllStagesStopped {
      val (prefixF, suffixF) = Source
        .empty[Int]
        .prefixAndDownstreamMat(7) { prefix =>
          Flow[Int].mapMaterializedValue(_ => prefix).mapConcat(n => List.fill(n - 6)(n))
        }(Keep.right)
        .toMat(Sink.seq[Int])(Keep.both)
        .run()

      prefixF.futureValue should be(empty)
      suffixF.futureValue should be(empty)
    }

    "apply materialized flow's semantics when upstream produces no elements" in assertAllStagesStopped {
      val (prefixF, suffixF) = Source
        .empty[Int]
        .prefixAndDownstreamMat(7) { prefix =>
          Flow[Int].mapMaterializedValue(_ => prefix).mapConcat(n => List.fill(n - 6)(n)).prepend(Source(100 to 101))
        }(Keep.right)
        .toMat(Sink.seq[Int])(Keep.both)
        .run()

      prefixF.futureValue should be(empty)
      suffixF.futureValue should ===(100 :: 101 :: Nil)
    }

    "handles upstream cancellation" in assertAllStagesStopped {
      val publisher = TestPublisher.manualProbe[Int]()
      val subscriber = TestSubscriber.manualProbe[Int]()

      val matValue = Source
        .fromPublisher(publisher)
        .prefixAndDownstreamMat(2) { prefix =>
          println("materializing flow")
          Flow[Int].mapMaterializedValue(_ => prefix).prepend(Source(100 to 101)).alsoTo(Sink.foreach(println(_)))
        }(Keep.right)
        .to(Sink.fromSubscriber(subscriber))
        .run()

      matValue.value should be(empty)

      val upstream = publisher.expectSubscription()
      val downstream = subscriber.expectSubscription()

      downstream.request(1000)

      upstream.expectRequest()
      //completing publisher
      upstream.sendComplete()

      matValue.futureValue should ===(Nil)

      subscriber.expectNext(100)

      subscriber.expectNext(101).expectComplete()

    }

    "work when materialized flow produces no downstream elements" in assertAllStagesStopped {
      val (prefixF, suffixF) = Source(0 until 100)
        .prefixAndDownstreamMat(4) { prefix =>
          Flow[Int].mapMaterializedValue(_ => prefix).filter(_ => false)
        }(Keep.right)
        .toMat(Sink.seq)(Keep.both)
        .run

      prefixF.futureValue should ===(0 until 4)
      suffixF.futureValue /*(Interval(Span(1000, Minutes)))*/ should be(empty)
    }

    "work when materialized flow does not consume upstream" in assertAllStagesStopped {
      val (prefixF, suffixF) = Source(0 until 100)
        .map { i =>
          i should be <= 4
          i
        }
        .prefixAndDownstreamMat(4) { prefix =>
          Flow[Int].mapMaterializedValue(_ => prefix).take(0)
        }(Keep.right)
        .toMat(Sink.seq)(Keep.both)
        .run

      prefixF.futureValue should ===(0 until 4)
      suffixF.futureValue /*(Interval(Span(1000, Minutes)))*/ should be(empty)
    }

    "work when materialized flow cancels upstream but keep producing" in assertAllStagesStopped {
      val (prefixF, suffixF) = src10()
        .prefixAndDownstreamMat(4) { prefix =>
          Flow[Int].mapMaterializedValue(_ => prefix).take(0).concat(Source(11 to 12))
        }(Keep.right)
        .toMat(Sink.seq)(Keep.both)
        .run

      prefixF.futureValue should ===(0 until 4)
      //Thread.sleep(1000 * 60 * 60)
      suffixF.futureValue /*(Interval(Span(1000, Minutes)))*/ should ===(11 :: 12 :: Nil)
    }

    "propagate materialization failure (when application of 'f' succeeds" in assertAllStagesStopped {
      val (prefixF, suffixF) = src10()
        .prefixAndDownstreamMat(4) { prefix =>
          Flow[Int].mapMaterializedValue(_ => throw TE(s"boom-bada-bang (${prefix.size})"))
        }(Keep.right)
        .toMat(Sink.seq)(Keep.both)
        .run

      prefixF.failed.futureValue should be(a[NeverMaterializedException])
      prefixF.failed.futureValue.getCause should ===(TE("boom-bada-bang (4)"))
      suffixF.failed.futureValue should ===(TE("boom-bada-bang (4)"))
    }

    "succeed when materialized flow completes downstream but keep consuming elements" in assertAllStagesStopped {
      val (prefixAndTailF, suffixF) = src10()
        .prefixAndDownstreamMat(4) { prefix =>
          Flow[Int]
            .mapMaterializedValue(_ => prefix)
            .viaMat {
              Flow.fromSinkAndSourceMat(Sink.seq[Int], Source.empty[Int])(Keep.left)
            }(Keep.both)
        }(Keep.right)
        .toMat(Sink.seq)(Keep.both)
        .run

      suffixF.futureValue should be(empty)
      val (prefix, suffix) = prefixAndTailF.futureValue
      prefix should ===(0 until 4)
      suffix.futureValue should ===(4 until 10)
    }

    "downstream cancellation is propagated via the materialized flow" in assertAllStagesStopped {
      val publisher = TestPublisher.manualProbe[Int]()
      val subscriber = TestSubscriber.manualProbe[Int]()

      val ((srcWatchTermF, notUsedF), suffixF) = src10()
        .watchTermination()(Keep.right)
        .prefixAndDownstreamMat(2) { prefix =>
          prefix should ===(0 until 2)
          Flow.fromSinkAndSource(Sink.fromSubscriber(subscriber), Source.fromPublisher(publisher))
        }(Keep.both)
        .take(1)
        .toMat(Sink.seq)(Keep.both)
        .run()

      notUsedF.value should be(empty)
      suffixF.value should be(empty)
      srcWatchTermF.value should be(empty)

      val subUpstream = publisher.expectSubscription()
      val subDownstream = subscriber.expectSubscription()

      notUsedF.futureValue should ===(NotUsed)

      subUpstream.expectRequest() should be >= (1L)
      subDownstream.request(1)
      subscriber.expectNext(2)
      subUpstream.sendNext(22)
      subUpstream.expectCancellation()
      subDownstream.cancel()

      suffixF.futureValue should ===(Seq(22))
      srcWatchTermF.futureValue should ===(Done)
    }

    "early downstream cancellation triggers flow materialization" in assertAllStagesStopped {
      val publisher = TestPublisher.manualProbe[Int]()
      val subscriber = TestSubscriber.manualProbe[Int]()

      val (srcWatchTermF, matFlowWatchTermFF) = Source.fromPublisher(publisher)
        .watchTermination()(Keep.right)
        .prefixAndDownstreamMat(8) { prefix =>
          println("materializing!")
          prefix should ===(0 until 2)
          Flow[Int].watchTermination()(Keep.right)
          //Flow.fromSinkAndSource(Sink.fromSubscriber(subscriber), Source.fromPublisher(publisher))
        }(Keep.both)
        .to(Sink.fromSubscriber(subscriber))
        .run()
      val matFlowWatchTerm = matFlowWatchTermFF.flatten

      matFlowWatchTerm.value should be(empty)
      srcWatchTermF.value should be(empty)

      val subDownstream = subscriber.expectSubscription()
      val subUpstream = publisher.expectSubscription()
      subDownstream.request(1)
      subUpstream.expectRequest() should be >= (1L)
      subUpstream.sendNext(0)
      //subUpstream.expectRequest() should be >= (1L)
      subUpstream.sendNext(1)
      //subUpstream.expectRequest() should be >= (1L)
      subDownstream.cancel()

      matFlowWatchTerm.futureValue should ===(Done)
      srcWatchTermF.futureValue should ===(Done)

      subUpstream.expectCancellation()
    }

    "early downstream failure triggers flow materialization" in assertAllStagesStopped {
      val publisher = TestPublisher.manualProbe[Int]()
      val subscriber = TestSubscriber.manualProbe[Int]()

      val (srcWatchTermF, matFlowWatchTermFF) = Source.fromPublisher(publisher)
        .watchTermination()(Keep.right)
        .prefixAndDownstreamMat(8) { prefix =>
          println("materializing!")
          prefix should ===(0 until 2)
          Flow[Int].watchTermination()(Keep.right)
          //Flow.fromSinkAndSource(Sink.fromSubscriber(subscriber), Source.fromPublisher(publisher))
        }(Keep.both)
        .to(Sink.fromSubscriber(subscriber))
        .run()
      val matFlowWatchTerm = matFlowWatchTermFF.flatten

      matFlowWatchTerm.value should be(empty)
      srcWatchTermF.value should be(empty)

      val subDownstream = subscriber.expectSubscription()
      val subUpstream = publisher.expectSubscription()
      subDownstream.request(1)
      subUpstream.expectRequest() should be >= (1L)
      subUpstream.sendNext(0)
      //subUpstream.expectRequest() should be >= (1L)
      subUpstream.sendNext(1)
      //subUpstream.expectRequest() should be >= (1L)
      subDownstream.asInstanceOf[SubscriptionWithCancelException].cancel(TE("that again?!"))

      matFlowWatchTerm.failed.futureValue should ===(TE("that again?!"))
      srcWatchTermF.failed.futureValue should ===(TE("that again?!"))

      subUpstream.expectCancellation()
    }

    "downstream failure is propagated via the materialized flow" in assertAllStagesStopped {
      val publisher = TestPublisher.manualProbe[Int]()
      val subscriber = TestSubscriber.manualProbe[Int]()

      val ((srcWatchTermF, notUsedF), suffixF) = src10()
        .watchTermination()(Keep.right)
        .prefixAndDownstreamMat(2) { prefix =>
          prefix should ===(0 until 2)
          Flow.fromSinkAndSourceCoupled(Sink.fromSubscriber(subscriber), Source.fromPublisher(publisher))
        }(Keep.both)
        .map {
          case 2 => 2
          case 3 => throw TE("3!?!?!?")
          case i => fail(s"unexpected value $i")
        }
        .toMat(Sink.seq)(Keep.both)
        .run()

      notUsedF.value should be(empty)
      suffixF.value should be(empty)
      srcWatchTermF.value should be(empty)

      val subUpstream = publisher.expectSubscription()
      val subDownstream = subscriber.expectSubscription()

      notUsedF.futureValue should ===(NotUsed)

      subUpstream.expectRequest() should be >= (1L)
      subDownstream.request(1)
      subscriber.expectNext(2)
      subUpstream.sendNext(2)
      subDownstream.request(1)
      subscriber.expectNext(3)
      subUpstream.sendNext(3)
      subUpstream.expectCancellation() should ===(TE("3!?!?!?"))
      subscriber.expectError(TE("3!?!?!?"))

      suffixF.failed.futureValue should ===(TE("3!?!?!?"))
      srcWatchTermF.failed.futureValue should ===(TE("3!?!?!?"))
    }

    "complete mat value with failures on abrupt termination before materializing the flow" in assertAllStagesStopped {
      val mat = Materializer(system)
      val publisher = TestPublisher.manualProbe[Int]()

      val flow = Source
        .fromPublisher(publisher)
        .prefixAndDownstreamMat(2) { prefix =>
          fail(s"unexpected prefix (length = ${prefix.size})")
          Flow[Int]
        }(Keep.right)
        .toMat(Sink.ignore)(Keep.both)

      val (prefixF, doneF) = flow.run()(mat)

      publisher.expectSubscription()
      prefixF.value should be(empty)
      doneF.value should be(empty)

      mat.shutdown()

      prefixF.failed.futureValue match {
        case _: AbruptTerminationException =>
        case ex: NeverMaterializedException =>
          ex.getCause should not be null
          ex.getCause should be(a[AbruptTerminationException])
      }
      doneF.failed.futureValue should be(a[AbruptTerminationException])
    }

    "behave like via when n = 0" in assertAllStagesStopped {
      val (prefixF, suffixF) = src10()
        .prefixAndDownstreamMat(0) { prefix =>
          prefix should be(empty)
          Flow[Int].mapMaterializedValue(_ => prefix)
        }(Keep.right)
        .toMat(Sink.seq)(Keep.both)
        .run()

      prefixF.futureValue should be(empty)
      suffixF.futureValue should ===(0 until 10)
    }

    "behave like via when n = 0 and upstream produces no elements" in assertAllStagesStopped {
      val (prefixF, suffixF) = Source
        .empty[Int]
        .prefixAndDownstreamMat(0) { prefix =>
          prefix should be(empty)
          Flow[Int].mapMaterializedValue(_ => prefix)
        }(Keep.right)
        .toMat(Sink.seq)(Keep.both)
        .run()

      prefixF.futureValue should be(empty)
      suffixF.futureValue should be(empty)
    }

    "propagate errors during flow's creation when n = 0" in assertAllStagesStopped {
      val (prefixF, suffixF) = src10()
        .prefixAndDownstreamMat(0) { prefix =>
          prefix should be(empty)
          throw TE("not this time my friend!")
          Flow[Int].mapMaterializedValue(_ => prefix)
        }(Keep.right)
        .toMat(Sink.seq)(Keep.both)
        .run()

      prefixF.failed.futureValue should be(a[NeverMaterializedException])
      prefixF.failed.futureValue.getCause === (TE("not this time my friend!"))
      suffixF.failed.futureValue should ===(TE("not this time my friend!"))
    }

    "propagate materialization failures when n = 0" in assertAllStagesStopped {
      val (prefixF, suffixF) = src10()
        .prefixAndDownstreamMat(0) { prefix =>
          prefix should be(empty)
          Flow[Int].mapMaterializedValue(_ => throw TE("Bang! no materialization this time"))
        }(Keep.right)
        .toMat(Sink.seq)(Keep.both)
        .run()

      prefixF.failed.futureValue should be(a[NeverMaterializedException])
      prefixF.failed.futureValue.getCause === (TE("Bang! no materialization this time"))
      suffixF.failed.futureValue should ===(TE("Bang! no materialization this time"))
    }

  }

}
