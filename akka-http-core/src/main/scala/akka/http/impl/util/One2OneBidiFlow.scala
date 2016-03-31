/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.impl.util

import akka.NotUsed
import akka.stream.scaladsl.BidiFlow

import scala.util.control.NoStackTrace
import akka.stream._
import akka.stream.stage.{ OutHandler, InHandler, GraphStageLogic, GraphStage }

/**
 * INTERNAL API
 */
private[http] object One2OneBidiFlow {

  case class UnexpectedOutputException(element: Any) extends RuntimeException(element.toString) with NoStackTrace
  case object OutputTruncationException
    extends RuntimeException("Inner stream finished before inputs completed. Outputs might have been truncated.")
    with NoStackTrace

  /**
   * Creates a generic ``BidiFlow`` which verifies that another flow produces exactly one output element per
   * input element, at the right time. Specifically it
   *
   * 1. triggers an ``UnexpectedOutputException`` if the inner flow produces an output element before having
   *    consumed the respective input element.
   * 2. triggers an `OutputTruncationException` if the inner flow completes before having produced an output element
   *    for every input element.
   * 3. triggers an `OutputTruncationException` if the inner flow cancels its inputs before the upstream completes its
   *    stream of inputs.
   * 4. Backpressures the input side if the maximum number of pending output elements has been reached,
   *    which is given via the ``maxPending`` parameter. You can use -1 to disable this feature.
   */
  def apply[I, O](maxPending: Int): BidiFlow[I, I, O, O, NotUsed] =
    BidiFlow.fromGraph(new One2OneBidi[I, O](maxPending))

  /*
   *    +--------------------+
   * ~> | in       toWrapped | ~>
   *    |                    |    
   * <~ | out    fromWrapped | <~
   *    +--------------------+
   */
  class One2OneBidi[I, O](maxPending: Int) extends GraphStage[BidiShape[I, I, O, O]] {
    val in = Inlet[I]("One2OneBidi.in")
    val out = Outlet[O]("One2OneBidi.out")
    val toWrapped = Outlet[I]("One2OneBidi.toWrapped")
    val fromWrapped = Inlet[O]("One2OneBidi.fromWrapped")

    override def initialAttributes = Attributes.name("One2OneBidi")
    val shape = BidiShape(in, toWrapped, fromWrapped, out)

    override def toString = "One2OneBidi"

    override def createLogic(effectiveAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
      private var insideWrappedFlow = 0
      private var pullSuppressed = false

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          insideWrappedFlow += 1
          push(toWrapped, grab(in))
        }
        override def onUpstreamFinish(): Unit = complete(toWrapped)
      })

      setHandler(toWrapped, new OutHandler {
        override def onPull(): Unit =
          if (insideWrappedFlow < maxPending || maxPending == -1) pull(in)
          else pullSuppressed = true
        override def onDownstreamFinish(): Unit = cancel(in)
      })

      setHandler(fromWrapped, new InHandler {
        override def onPush(): Unit = {
          val element = grab(fromWrapped)
          if (insideWrappedFlow > 0) {
            insideWrappedFlow -= 1
            push(out, element)
            if (pullSuppressed) {
              pullSuppressed = false
              pull(in)
            }
          } else throw new UnexpectedOutputException(element)
        }
        override def onUpstreamFinish(): Unit = {
          if (insideWrappedFlow > 0) throw OutputTruncationException
          else completeStage()
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = pull(fromWrapped)
        override def onDownstreamFinish(): Unit = cancel(fromWrapped)
      })
    }
  }
}
