/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.util.control.NoStackTrace
import akka.stream._
import akka.stream.stage.{ OutHandler, InHandler, GraphStageLogic, GraphStage }

object One2OneBidiFlow {

  case class UnexpectedOutputException(element: Any) extends RuntimeException(element.toString) with NoStackTrace
  case object OutputTruncationException extends RuntimeException with NoStackTrace

  /**
   * Creates a generic ``BidiFlow`` which verifies that another flow produces exactly one output element per
   * input element, at the right time. Specifically it
   *
   * 1. triggers an ``UnexpectedOutputException`` if the inner flow produces an output element before having
   *    consumed the respective input element.
   * 2. triggers an `OutputTruncationException` if the inner flow completes before having produced an output element
   *    for every input element.
   * 3. Backpressures the input side if the maximum number of pending output elements has been reached,
   *    which is given via the ``maxPending`` parameter. You can use -1 to disable this feature.
   */
  def apply[I, O](maxPending: Int): BidiFlow[I, I, O, O, Unit] =
    BidiFlow.fromGraph(new One2OneBidi[I, O](maxPending))

  class One2OneBidi[I, O](maxPending: Int) extends GraphStage[BidiShape[I, I, O, O]] {
    val inIn = Inlet[I]("inIn")
    val inOut = Outlet[I]("inOut")
    val outIn = Inlet[O]("outIn")
    val outOut = Outlet[O]("outOut")

    override def initialAttributes = Attributes.name("One2OneBidi")
    val shape = BidiShape(inIn, inOut, outIn, outOut)

    override def toString = "One2OneBidi"

    override def createLogic(effectiveAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
      private var pending = 0
      private var pullSuppressed = false

      setHandler(inIn, new InHandler {
        override def onPush(): Unit = {
          pending += 1
          push(inOut, grab(inIn))
        }
        override def onUpstreamFinish(): Unit = complete(inOut)
      })

      setHandler(inOut, new OutHandler {
        override def onPull(): Unit =
          if (pending < maxPending || maxPending == -1) pull(inIn)
          else pullSuppressed = true
        override def onDownstreamFinish(): Unit = cancel(inIn)
      })

      setHandler(outIn, new InHandler {
        override def onPush(): Unit = {
          val element = grab(outIn)
          if (pending > 0) {
            pending -= 1
            push(outOut, element)
            if (pullSuppressed) {
              pullSuppressed = false
              pull(inIn)
            }
          } else throw new UnexpectedOutputException(element)
        }
        override def onUpstreamFinish(): Unit =
          if (pending == 0) complete(outOut)
          else throw OutputTruncationException
      })

      setHandler(outOut, new OutHandler {
        override def onPull(): Unit = pull(outIn)
        override def onDownstreamFinish(): Unit = cancel(outIn)
      })
    }
  }
}
