/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.stream.{ scaladsl, ActorMaterializerSettings }
import akka.stream.impl.FanOut.OutputBunch
import akka.stream.{ Shape, OutPort, Outlet }

import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
private[akka] class FlexiRouteImpl[T, S <: Shape](_settings: ActorMaterializerSettings,
                                                  shape: S,
                                                  val routeLogic: scaladsl.FlexiRoute.RouteLogic[T])
  extends FanOut(_settings, shape.outlets.size) {

  import akka.stream.scaladsl.FlexiRoute._

  private type StateT = routeLogic.State[_]
  private type CompletionT = routeLogic.CompletionHandling

  val outputMapping: Array[Outlet[_]] = shape.outlets.toArray
  val indexOf: Map[OutPort, Int] = shape.outlets.zipWithIndex.toMap

  private def anyBehavior = behavior.asInstanceOf[routeLogic.State[Outlet[Any]]]
  private var behavior: StateT = _
  private var completion: CompletionT = _
  // needed to ensure that at most one element is emitted from onInput
  private val emitted = Array.ofDim[Boolean](outputCount)

  override def preStart(): Unit = {
    super.preStart()
    routeLogic.preStart()
  }

  override def postStop(): Unit = {
    try routeLogic.postStop()
    finally super.postStop()
  }

  override protected val outputBunch = new OutputBunch(outputCount, self, this) {
    override def onCancel(output: Int): Unit =
      changeBehavior(
        try completion.onDownstreamFinish(ctx, outputMapping(output))
        catch {
          case NonFatal(e) ⇒ fail(e); routeLogic.SameState
        })
  }

  override protected val primaryInputs: Inputs = new BatchingInputBuffer(settings.maxInputBufferSize, this) {
    override def onError(e: Throwable): Unit = {
      try completion.onUpstreamFailure(ctx, e) catch { case NonFatal(e) ⇒ fail(e) }
      fail(e)
    }

    override def onComplete(): Unit = {
      try completion.onUpstreamFinish(ctx) catch { case NonFatal(e) ⇒ fail(e) }
      super.onComplete()
    }
  }

  private val ctx: routeLogic.RouteLogicContext = new routeLogic.RouteLogicContext {

    override def emit[Out](output: Outlet[Out])(elem: Out): Unit = {
      val idx = indexOf(output)
      require(outputBunch.isPending(idx), s"emit to [$output] not allowed when no demand available")
      if (emitted(idx))
        throw new IllegalStateException("It is only allowed to `emit` at most one element to each output in response to `onInput`")
      emitted(idx) = true
      outputBunch.enqueue(idx, elem)
    }

    override def finish(): Unit = {
      primaryInputs.cancel()
      outputBunch.complete()
      context.stop(self)
    }

    override def finish(output: OutPort): Unit =
      outputBunch.complete(indexOf(output))

    override def fail(cause: Throwable): Unit = FlexiRouteImpl.this.fail(cause)

    override def fail(output: OutPort, cause: Throwable): Unit =
      outputBunch.error(indexOf(output), cause)

    override def changeCompletionHandling(newCompletion: CompletionT): Unit =
      FlexiRouteImpl.this.changeCompletionHandling(newCompletion)

  }

  private def markOutputs(outputs: Array[OutPort]): Unit = {
    outputBunch.unmarkAllOutputs()
    var i = 0
    while (i < outputs.length) {
      val id = indexOf(outputs(i))
      if (!outputBunch.isCancelled(id) && !outputBunch.isCompleted(id))
        outputBunch.markOutput(id)
      i += 1
    }
  }

  private def precondition: TransferState = {
    behavior.condition match {
      case _: DemandFrom[_] | _: DemandFromAny ⇒ primaryInputs.NeedsInput && outputBunch.AnyOfMarkedOutputs
      case _: DemandFromAll                    ⇒ primaryInputs.NeedsInput && outputBunch.AllOfMarkedOutputs
    }
  }

  private def changeCompletionHandling(newCompletion: CompletionT): Unit =
    completion = newCompletion.asInstanceOf[CompletionT]

  private def changeBehavior[A](newBehavior: routeLogic.State[A]): Unit =
    if (newBehavior != routeLogic.SameState && (newBehavior ne behavior)) {
      behavior = newBehavior.asInstanceOf[StateT]
      behavior.condition match {
        case any: DemandFromAny ⇒
          markOutputs(any.outputs.toArray)
        case all: DemandFromAll ⇒
          markOutputs(all.outputs.toArray)
        case DemandFrom(output) ⇒
          require(indexOf.contains(output), s"Unknown output handle $output")
          val idx = indexOf(output)
          outputBunch.unmarkAllOutputs()
          outputBunch.markOutput(idx)
      }
    }

  changeBehavior(routeLogic.initialState)
  changeCompletionHandling(routeLogic.initialCompletionHandling)

  initialPhase(1, TransferPhase(precondition) { () ⇒
    val elem = primaryInputs.dequeueInputElement().asInstanceOf[T]
    behavior.condition match {
      case any: DemandFromAny ⇒
        val id = outputBunch.idToEnqueueAndYield()
        val outputHandle = outputMapping(id)
        callOnInput(behavior.asInstanceOf[routeLogic.State[OutPort]], outputHandle, elem)

      case DemandFrom(outputHandle) ⇒
        callOnInput(anyBehavior, outputHandle, elem)

      case all: DemandFromAll ⇒
        callOnInput(behavior.asInstanceOf[routeLogic.State[Unit]], (), elem)
    }

  })

  private def callOnInput[U](b: routeLogic.State[U], output: U, element: T): Unit = {
    java.util.Arrays.fill(emitted, false)
    changeBehavior(b.onInput(ctx, output, element))
  }

}
