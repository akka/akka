/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.testkit

import akka.NotUsed
import akka.actor.{ ActorRef, ActorRefWithCell }
import akka.annotation.InternalApi
import akka.stream.Attributes
import akka.stream.impl.fusing.GraphStages.SimpleLinearGraphStage
import akka.stream.scaladsl.Flow
import akka.stream.stage.{ GraphStageLogic, InHandler, OutHandler, StageLogging, TimerGraphStageLogic }
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration.{ Duration, FiniteDuration }
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
   * INTERNAL API.
   *
   * Returns a flow that is almost identity but delays propagation of cancellation from downstream to upstream.
   *
   * Borrowed from https://github.com/akka/akka-http/blob/d85b71df0f01ce80f65fbb17e513fc2beb6d5adf/akka-http-core/src/main/scala/akka/http/impl/util/StreamUtils.scala#L193
   */
  @InternalApi private[akka] def delayCancellation[T](cancelAfter: Duration): Flow[T, T, NotUsed] = {
    final class DelayCancellationStage[S](cancelAfter: Duration) extends SimpleLinearGraphStage[S] {
      def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
        new TimerGraphStageLogic(shape) with InHandler with OutHandler with StageLogging {
          setHandlers(in, out, this)

          def onPush(): Unit =
            push(out, grab(in)) // using `passAlong` was considered but it seems to need some boilerplate to make it work
          def onPull(): Unit = pull(in)

          final val CompleteStageTimer = "cst"

          override def onDownstreamFinish(cause: Throwable): Unit = {
            cancelAfter match {
              case finite: FiniteDuration =>
                log.debug(s"Delaying cancellation for $finite")
                scheduleOnce(CompleteStageTimer, finite)
              case _ => // do nothing
            }

            // don't pass cancellation to upstream but keep pulling until we get completion or failure
            setHandler(in, new InHandler {
              if (!hasBeenPulled(in)) pull(in)

              def onPush(): Unit = {
                grab(in) // ignore further elements
                pull(in)
              }
            })
          }

          override def onTimer(timerKey: Any): Unit = {
            log.debug(s"Stage was canceled after delay of $cancelAfter")
            completeStage()
          }

          override def postStop(): Unit = cancelTimer(CompleteStageTimer)
        }
    }

    Flow.fromGraph(new DelayCancellationStage(cancelAfter))
  }

}
