/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.testkit

import akka.NotUsed
import akka.actor.{ActorRef, ActorRefWithCell, Cancellable}
import akka.stream.Attributes
import akka.stream.impl.fusing.GraphStages.SimpleLinearGraphStage
import akka.stream.scaladsl.Flow
import akka.stream.stage.{GraphStageLogic, InHandler, OutHandler, StageLogging}
import akka.util.OptionVal
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.control.NoStackTrace

object Utils {

  /** Sets the default-mailbox to the usual [[akka.dispatch.UnboundedMailbox]] instead of [[StreamTestDefaultMailbox]]. */
  val UnboundedMailboxConfig =
    ConfigFactory.parseString("""akka.actor.default-mailbox.mailbox-type = "akka.dispatch.UnboundedMailbox"""")

  case class TE(message: String) extends RuntimeException(message) with NoStackTrace

  def assertDispatcher(ref: ActorRef, dispatcher: String): Unit = ref match {
    case r: ActorRefWithCell =>
      if (r.underlying.props.dispatcher != dispatcher)
        throw new AssertionError(
          s"Expected $ref to use dispatcher [$dispatcher], yet used: [${r.underlying.props.dispatcher}]")
    case _ =>
      throw new Exception(s"Unable to determine dispatcher of $ref")
  }

  /**
   * INTERNAL API
   *
   * Returns a flow that is almost identity but delays propagation of cancellation from downstream to upstream.
   *
   * Borrowed from https://github.com/akka/akka-http/blob/d85b71df0f01ce80f65fbb17e513fc2beb6d5adf/akka-http-core/src/main/scala/akka/http/impl/util/StreamUtils.scala#L193
   */
  def delayCancellation[T](cancelAfter: Duration): Flow[T, T, NotUsed] = Flow.fromGraph(new DelayCancellationStage(cancelAfter))
  final class DelayCancellationStage[T](cancelAfter: Duration) extends SimpleLinearGraphStage[T] {
    def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with ScheduleSupport with InHandler with OutHandler with StageLogging {
      setHandlers(in, out, this)

      def onPush(): Unit = push(out, grab(in)) // using `passAlong` was considered but it seems to need some boilerplate to make it work
      def onPull(): Unit = pull(in)

      var timeout: OptionVal[Cancellable] = OptionVal.None

      override def onDownstreamFinish(): Unit = {
        cancelAfter match {
          case finite: FiniteDuration =>
            log.debug(s"Delaying cancellation for $finite")
            timeout = OptionVal.Some {
              scheduleOnce(finite) {
                log.debug(s"Stage was canceled after delay of $cancelAfter")
                timeout = OptionVal.None
                completeStage()
              }
            }
          case _ => // do nothing
        }

        // don't pass cancellation to upstream but keep pulling until we get completion or failure
        setHandler(
          in,
          new InHandler {
            if (!hasBeenPulled(in)) pull(in)

            def onPush(): Unit = {
              grab(in) // ignore further elements
              pull(in)
            }
          }
        )
      }

      override def postStop(): Unit = timeout match {
        case OptionVal.Some(x) => x.cancel()
        case OptionVal.None    => // do nothing
      }
    }
  }

  trait ScheduleSupport { self: GraphStageLogic =>
    /**
     * Schedule a block to be run once after the given duration in the context of this graph stage.
     */
    def scheduleOnce(delay: FiniteDuration)(block: => Unit): Cancellable =
      materializer.scheduleOnce(delay, new Runnable { def run() = runInContext(block) })

    def runInContext(block: => Unit): Unit = getAsyncCallback[AnyRef](_ => block).invoke(null)
  }
}
