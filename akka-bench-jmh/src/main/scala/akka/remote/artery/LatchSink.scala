/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.util.concurrent.CountDownLatch

import akka.stream.Attributes
import akka.stream.Inlet
import akka.stream.SinkShape
import akka.stream.stage.GraphStage
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.InHandler

class LatchSink(countDownAfter: Int, latch: CountDownLatch) extends GraphStage[SinkShape[Any]] {
  val in: Inlet[Any] = Inlet("LatchSink")
  override val shape: SinkShape[Any] = SinkShape(in)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler {

      var n = 0

      override def preStart(): Unit = pull(in)

      override def onPush(): Unit = {
        n += 1
        if (n == countDownAfter)
          latch.countDown()
        grab(in)
        pull(in)
      }

      setHandler(in, this)
    }
}
