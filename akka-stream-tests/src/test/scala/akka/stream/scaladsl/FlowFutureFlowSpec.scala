/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.NotUsed
import akka.stream.NeverMaterializedException
import akka.stream.testkit.StreamSpec
import akka.stream.testkit.Utils.TE
import akka.stream.testkit.scaladsl.StreamTestKit.assertAllStagesStopped

import scala.concurrent.{Future, Promise}

class FlowFutureFlowSpec extends StreamSpec {
  def src10(i: Int = 0) = Source(i until (i + 10))

  "a futureFlow" must {
    "work in the simple case with a completed future" in assertAllStagesStopped {
      val (fNotUsed, fSeq) = src10()
        .viaMat{
          Flow.futureFlow{
            Future.successful(Flow[Int])
          }
        } (Keep.right)
        .toMat(Sink.seq)(Keep.both)
        .run()

      fNotUsed.futureValue should be (NotUsed)
      fSeq.futureValue should equal (0 until 10)
    }

    "work in the simple case with a late future" in assertAllStagesStopped {
      val prFlow = Promise[Flow[Int, Int, NotUsed]]
      val (fNotUsed, fSeq) = src10()
        .viaMat{
          Flow.futureFlow(prFlow.future)
        } (Keep.right)
        .toMat(Sink.seq)(Keep.both)
        .run()

      fNotUsed.value should be (empty)
      fSeq.value should be (empty)

      prFlow.success(Flow[Int])

      fNotUsed.futureValue should be (NotUsed)
      fSeq.futureValue should equal (0 until 10)
    }

    "fail properly when future is a completed failed future" in assertAllStagesStopped {
      val (fNotUsed, fSeq) = src10()
        .viaMat{
          Flow.futureFlow{
            Future.failed[Flow[Int, Int, NotUsed]](TE("damn!"))
          }
        } (Keep.right)
        .toMat(Sink.seq)(Keep.both)
        .run()

      fNotUsed.failed.futureValue should be (a[NeverMaterializedException])
      fNotUsed.failed.futureValue.getCause should equal (TE("damn!"))

      fSeq.failed.futureValue should equal (TE("damn!"))

    }

    "fail properly when future is late completed failed future" in assertAllStagesStopped {
      val prFlow = Promise[Flow[Int, Int, NotUsed]]
      val (fNotUsed, fSeq) = src10()
        .viaMat{
          Flow.futureFlow(prFlow.future)
        } (Keep.right)
        .toMat(Sink.seq)(Keep.both)
        .run()

      fNotUsed.value should be (empty)
      fSeq.value should be (empty)

      prFlow.failure(TE("damn!"))

      fNotUsed.failed.futureValue should be (a[NeverMaterializedException])
      fNotUsed.failed.futureValue.getCause should equal (TE("damn!"))

      fSeq.failed.futureValue should equal (TE("damn!"))

    }
  }



}
