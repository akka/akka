/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import akka.NotUsed
import akka.actor.Cancellable
import akka.stream.impl.fusing.GraphStages.SimpleLinearGraphStage
import akka.stream.scaladsl.Flow
import akka.stream.stage.{ GraphStageLogic, InHandler, OutHandler, StageLogging }
import akka.util.OptionVal

import scala.concurrent.duration.{ Duration, FiniteDuration }

private[akka] object StreamUtils {
  /**
   * INTERNAL API
   *
   * Returns a flow that is almost identity but delays propagation of cancellation from downstream to upstream.
   *
   * Once the down stream is finish calls to onPush are ignored.
   */
  def delayCancellation[T](cancelAfter: Duration): Flow[T, T, NotUsed] = Flow.fromGraph(new DelayCancellationStage(cancelAfter))

  final class DelayCancellationStage[T](cancelAfter: Duration) extends SimpleLinearGraphStage[T] {
    def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with InHandler with OutHandler with StageLogging {
      setHandlers(in, out, this)

      def onPush(): Unit = push(out, grab(in)) // using `passAlong` was considered but it seems to need some boilerplate to make it work
      def onPull(): Unit = pull(in)

      var timeout: OptionVal[Cancellable] = OptionVal.None

      override def onDownstreamFinish(): Unit = {
        cancelAfter match {
          case finite: FiniteDuration ⇒
            timeout = OptionVal.Some {
              scheduleOnce(finite) {
                log.debug(s"Stage was canceled after delay of $cancelAfter")
                completeStage()
              }
            }
          case _ ⇒
        }

        setHandler(
          in,
          new InHandler {
            def onPush(): Unit = {}
          }
        )
      }

      override def postStop(): Unit = timeout match {
        case OptionVal.Some(x) ⇒ x.cancel()
        case OptionVal.None    ⇒
      }

      def scheduleOnce(delay: FiniteDuration)(block: ⇒ Unit): Cancellable =
        materializer.scheduleOnce(delay, new Runnable {
          def run() = runInContext(block)
        })

      def runInContext(block: ⇒ Unit): Unit = getAsyncCallback[AnyRef](_ ⇒ block).invoke(null)
    }
  }
}
