/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import scala.collection.immutable

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Props
import akka.stream.MaterializerSettings
import org.reactivestreams.Subscription

/**
 * INTERNAL API
 */
private[akka] object FanOut {

  case class SubstreamRequestMore(id: Int, demand: Long)
  case class SubstreamCancel(id: Int)
  case class SubstreamSubscribePending(id: Int)

  class SubstreamSubscription(val parent: ActorRef, val id: Int) extends Subscription {
    override def request(elements: Long): Unit =
      if (elements <= 0) throw new IllegalArgumentException("The number of requested elements must be > 0")
      else parent ! SubstreamRequestMore(id, elements)
    override def cancel(): Unit = parent ! SubstreamCancel(id)
    override def toString = "SubstreamSubscription" + System.identityHashCode(this)
  }

  class FanoutOutputs(val id: Int, _impl: ActorRef, _pump: Pump) extends SimpleOutputs(_impl, _pump) {
    override def createSubscription(): Subscription = new SubstreamSubscription(actor, id)
  }

  case class ExposedPublishers(publishers: immutable.Seq[ActorPublisher[Any]])

  class OutputBunch(outputCount: Int, impl: ActorRef, pump: Pump) {
    private var bunchCancelled = false

    private val outputs = Array.tabulate(outputCount)(new FanoutOutputs(_, impl, pump))

    private val marked = Array.ofDim[Boolean](outputCount)
    private var markedCount = 0
    private val pending = Array.ofDim[Boolean](outputCount)
    private var markedPending = 0
    private val cancelled = Array.ofDim[Boolean](outputCount)
    private var markedCancelled = 0
    private val completed = Array.ofDim[Boolean](outputCount)
    private val errored = Array.ofDim[Boolean](outputCount)

    private var unmarkCancelled = true

    private var preferredId = 0

    def isPending(output: Int): Boolean = pending(output)

    def isCompleted(output: Int): Boolean = completed(output)

    def isCancelled(output: Int): Boolean = cancelled(output)

    def complete(): Unit =
      if (!bunchCancelled) {
        bunchCancelled = true
        var i = 0
        while (i < outputs.length) {
          complete(i)
          i += 1
        }
      }

    def complete(output: Int) =
      if (!completed(output) && !errored(output) && !cancelled(output)) {
        outputs(output).complete()
        completed(output) = true
        unmarkOutput(output)
      }

    def cancel(e: Throwable): Unit =
      if (!bunchCancelled) {
        bunchCancelled = true
        var i = 0
        while (i < outputs.length) {
          error(i, e)
          i += 1
        }
      }

    def error(output: Int, e: Throwable): Unit =
      if (!errored(output) && !cancelled(output) && !completed(output)) {
        outputs(output).cancel(e)
        errored(output) = true
        unmarkOutput(output)
      }

    def markOutput(output: Int): Unit = {
      if (!marked(output)) {
        if (cancelled(output)) markedCancelled += 1
        if (pending(output)) markedPending += 1
        marked(output) = true
        markedCount += 1
      }
    }

    def unmarkOutput(output: Int): Unit = {
      if (marked(output)) {
        if (cancelled(output)) markedCancelled -= 1
        if (pending(output)) markedPending -= 1
        marked(output) = false
        markedCount -= 1
      }
    }

    def markAllOutputs(): Unit = {
      var i = 0
      while (i < outputCount) {
        markOutput(i)
        i += 1
      }
    }

    def unmarkAllOutputs(): Unit = {
      var i = 0
      while (i < outputCount) {
        unmarkOutput(i)
        i += 1
      }
    }

    def unmarkCancelledOutputs(enabled: Boolean): Unit = unmarkCancelled = enabled

    def idToEnqueue(): Int = {
      var id = preferredId
      while (!(marked(id) && pending(id))) {
        id += 1
        if (id == outputCount) id = 0
        assert(id != preferredId, "Tried to enqueue without waiting for any demand")
      }
      id
    }

    def enqueue(id: Int, elem: Any): Unit = {
      val output = outputs(id)
      output.enqueueOutputElement(elem)
      if (!output.demandAvailable) {
        if (marked(id)) markedPending -= 1
        pending(id) = false
      }
    }

    def enqueueMarked(elem: Any): Unit = {
      var id = 0
      while (id < outputCount) {
        if (marked(id)) enqueue(id, elem)
        id += 1
      }
    }

    def idToEnqueueAndYield(): Int = {
      val id = idToEnqueue()
      preferredId = id + 1
      if (preferredId == outputCount) preferredId = 0
      id
    }

    def enqueueAndYield(elem: Any): Unit = {
      val id = idToEnqueueAndYield()
      enqueue(id, elem)
    }

    def enqueueAndPrefer(elem: Any, preferred: Int): Unit = {
      val id = idToEnqueue()
      preferredId = preferred
      enqueue(id, elem)
    }

    def onCancel(output: Int): Unit = ()

    /**
     * Will only transfer an element when all marked outputs
     * have demand, and will complete as soon as any of the marked
     * outputs have cancelled.
     */
    val AllOfMarkedOutputs = new TransferState {
      override def isCompleted: Boolean = markedCancelled > 0 || markedCount == 0
      override def isReady: Boolean = markedPending == markedCount
    }

    /**
     * Will transfer an element when any of the  marked outputs
     * have demand, and will complete when all of the marked
     * outputs have cancelled.
     */
    val AnyOfMarkedOutputs = new TransferState {
      override def isCompleted: Boolean = markedCancelled == markedCount
      override def isReady: Boolean = markedPending > 0
    }

    // FIXME: Eliminate re-wraps
    def subreceive: SubReceive = new SubReceive({
      case ExposedPublishers(publishers) ⇒
        publishers.zip(outputs) foreach {
          case (pub, output) ⇒
            output.subreceive(ExposedPublisher(pub))
        }

      case SubstreamRequestMore(id, demand) ⇒
        if (marked(id) && !pending(id)) markedPending += 1
        pending(id) = true
        outputs(id).subreceive(RequestMore(null, demand))
      case SubstreamCancel(id) ⇒
        if (unmarkCancelled) {
          unmarkOutput(id)
        }
        if (marked(id) && !cancelled(id)) markedCancelled += 1
        cancelled(id) = true
        onCancel(id)
        outputs(id).subreceive(Cancel(null))
      case SubstreamSubscribePending(id) ⇒
        outputs(id).subreceive(SubscribePending)
    })

  }

}

/**
 * INTERNAL API
 */
private[akka] abstract class FanOut(val settings: MaterializerSettings, val outputPorts: Int) extends Actor with ActorLogging with Pump {
  import FanOut._

  protected val outputBunch = new OutputBunch(outputPorts, self, this)
  protected val primaryInputs: Inputs = new BatchingInputBuffer(settings.maxInputBufferSize, this) {
    override def onError(e: Throwable): Unit = fail(e)
  }

  override def pumpFinished(): Unit = {
    primaryInputs.cancel()
    outputBunch.complete()
    context.stop(self)
  }

  override def pumpFailed(e: Throwable): Unit = fail(e)

  protected def fail(e: Throwable): Unit = {
    log.error(e, "failure during processing") // FIXME: escalate to supervisor instead
    primaryInputs.cancel()
    outputBunch.cancel(e)
    context.stop(self)
  }

  override def postStop(): Unit = {
    primaryInputs.cancel()
    outputBunch.cancel(new IllegalStateException("Processor actor terminated abruptly"))
  }

  override def postRestart(reason: Throwable): Unit = {
    super.postRestart(reason)
    throw new IllegalStateException("This actor cannot be restarted")
  }

  def receive = primaryInputs.subreceive orElse outputBunch.subreceive

}

/**
 * INTERNAL API
 */
private[akka] object Broadcast {
  def props(settings: MaterializerSettings, outputPorts: Int): Props =
    Props(new Broadcast(settings, outputPorts))
}

/**
 * INTERNAL API
 */
private[akka] class Broadcast(_settings: MaterializerSettings, _outputPorts: Int) extends FanOut(_settings, _outputPorts) {
  outputBunch.markAllOutputs()

  nextPhase(TransferPhase(primaryInputs.NeedsInput && outputBunch.AllOfMarkedOutputs) { () ⇒
    val elem = primaryInputs.dequeueInputElement()
    outputBunch.enqueueMarked(elem)
  })
}

/**
 * INTERNAL API
 */
private[akka] object Balance {
  def props(settings: MaterializerSettings, outputPorts: Int, waitForAllDownstreams: Boolean): Props =
    Props(new Balance(settings, outputPorts, waitForAllDownstreams))
}

/**
 * INTERNAL API
 */
private[akka] class Balance(_settings: MaterializerSettings, _outputPorts: Int, waitForAllDownstreams: Boolean) extends FanOut(_settings, _outputPorts) {
  outputBunch.markAllOutputs()

  val runningPhase = TransferPhase(primaryInputs.NeedsInput && outputBunch.AnyOfMarkedOutputs) { () ⇒
    val elem = primaryInputs.dequeueInputElement()
    outputBunch.enqueueAndYield(elem)
  }

  if (waitForAllDownstreams)
    nextPhase(TransferPhase(primaryInputs.NeedsInput && outputBunch.AllOfMarkedOutputs) { () ⇒
      nextPhase(runningPhase)
    })
  else
    nextPhase(runningPhase)
}

/**
 * INTERNAL API
 */
private[akka] object Unzip {
  def props(settings: MaterializerSettings): Props =
    Props(new Unzip(settings))
}

/**
 * INTERNAL API
 */
private[akka] class Unzip(_settings: MaterializerSettings) extends FanOut(_settings, outputPorts = 2) {
  outputBunch.markAllOutputs()

  nextPhase(TransferPhase(primaryInputs.NeedsInput && outputBunch.AllOfMarkedOutputs) { () ⇒
    primaryInputs.dequeueInputElement() match {
      case (a, b) ⇒
        outputBunch.enqueue(0, a)
        outputBunch.enqueue(1, b)

      case t: akka.japi.Pair[_, _] ⇒
        outputBunch.enqueue(0, t.first)
        outputBunch.enqueue(1, t.second)

      case t ⇒
        throw new IllegalArgumentException(
          s"Unable to unzip elements of type ${t.getClass.getName}, " +
            s"can only handle Tuple2 and akka.japi.Pair!")
    }
  })
}
