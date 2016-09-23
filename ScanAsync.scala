package akka.stream.impl.contrib

import akka.stream.ActorAttributes.SupervisionStrategy
import akka.stream._
import akka.stream.impl.ReactiveStreamsCompliance
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}

import scala.concurrent.Future
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

final case class ScanAsync[In, Out](zero: Out)(f: (Out, In) => Future[Out]) extends GraphStage[FlowShape[In, Out]] {

  import akka.dispatch.ExecutionContexts

  val in = Inlet[In]("ScanAsync.in")
  val out = Outlet[Out]("ScanAsync.out")
  override val shape: FlowShape[In, Out] = FlowShape[In, Out](in, out)

  override val initialAttributes: Attributes = Attributes.name("scanAsync")

  override val toString: String = "ScanAsync"

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler {
      self =>

      private var aggregator: Out = zero
      private var aggregating: Future[Out] = Future.successful(aggregator)

      private val ZeroHandler: OutHandler with InHandler = new OutHandler with InHandler {
        override def onPush(): Unit = ()

        override def onUpstreamFinish(): Unit = setHandler(out, new OutHandler {
          override def onPull(): Unit = {
            push(out, aggregator)
            completeStage()
          }
        })

        override def onPull(): Unit = {
          push(out, aggregator)
          setHandlers(in, out, self)
        }
      }

      private def onRestart(t: Throwable): Unit = {
        aggregator = zero
      }

      private def ec = ExecutionContexts.sameThreadExecutionContext

      private lazy val decider = inheritedAttributes.get[SupervisionStrategy].map(_.decider).getOrElse(Supervision.stoppingDecider)

      private def pushAndPullOrFinish(update: Out): Unit = {
        push(out, update)
        if (isClosed(in)) {
          completeStage()
        } else if (isAvailable(out) && !hasBeenPulled(in)) {
          tryPull(in)
        }
      }

      private val futureCB = getAsyncCallback[Try[Out]] {
        case Success(update) if update != null =>
          aggregator = update
          pushAndPullOrFinish(update)
        case other =>
          val ex = other match {
            case Failure(t) => t
            case Success(s) if s == null => ReactiveStreamsCompliance.elementMustNotBeNullException
          }

          val supervision = decider(ex)

          supervision match {
            case Supervision.Stop => failStage(ex)
            case _ =>
              if (supervision == Supervision.Restart) onRestart(ex)
              pushAndPullOrFinish(aggregator)
          }
      }.invoke _

      setHandlers(in, out, ZeroHandler)

      def onPull(): Unit = if (!hasBeenPulled(in)) tryPull(in)

      def onPush(): Unit = {
        try {
          aggregating = f(aggregator, grab(in))

          aggregating.value match {
            case Some(result) => futureCB(result)
            case _ => aggregating.onComplete(futureCB)(ec)
          }
        } catch {
          case NonFatal(ex) =>
            decider(ex) match {
              case Supervision.Stop ⇒ failStage(ex)
              case Supervision.Restart => onRestart(ex)
              case _ => ()
            }
            tryPull(in)
        }
      }

      override def onUpstreamFinish(): Unit = {}

      override val toString: String = s"ScanAsync.Logic(completed=${aggregating.isCompleted})"
    }
}
