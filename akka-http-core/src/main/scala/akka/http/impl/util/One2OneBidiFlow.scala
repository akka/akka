/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.impl.util

import akka.NotUsed
import akka.annotation.InternalApi
import akka.stream.scaladsl.BidiFlow

import akka.stream._
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }

/**
 * INTERNAL API
 */
@InternalApi
private[http] object One2OneBidiFlow {

  case class UnexpectedOutputException(element: Any)
    extends RuntimeException(s"Inner flow produced unexpected result element '$element' when no element was outstanding")
  case class OutputTruncationException(missingOutputElements: Int)
    extends RuntimeException(s"Inner flow was completed without producing result elements for $missingOutputElements outstanding elements")

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
  def apply[I, O](
    maxPending:                Int,
    outputTruncationException: Int ⇒ Throwable = OutputTruncationException,
    unexpectedOutputException: Any ⇒ Throwable = UnexpectedOutputException): BidiFlow[I, I, O, O, NotUsed] =
    BidiFlow.fromGraph(new One2OneBidi[I, O](maxPending, outputTruncationException, unexpectedOutputException))

  /*
   *    +--------------------+
   * ~> | in       toWrapped | ~>
   *    |                    |
   * <~ | out    fromWrapped | <~
   *    +--------------------+
   */
  class One2OneBidi[I, O](
    maxPending:                Int,
    outputTruncationException: Int ⇒ Throwable,
    unexpectedOutputException: Any ⇒ Throwable) extends GraphStage[BidiShape[I, I, O, O]] {
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
              if (!isClosed(in)) pull(in)
            }
          } else failStage(unexpectedOutputException(element))
        }
        override def onUpstreamFinish(): Unit = {
          if (insideWrappedFlow > 0) failStage(outputTruncationException(insideWrappedFlow))
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
