/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.stream.ActorFlowMaterializer
import akka.stream.scaladsl.Sink
import akka.actor.{ Deploy, Props }

/**
 * INTERNAL API
 */
private[akka] object ConcatAllImpl {
  def props(materializer: ActorFlowMaterializer): Props =
    Props(new ConcatAllImpl(materializer)).withDeploy(Deploy.local)
}

/**
 * INTERNAL API
 */
private[akka] class ConcatAllImpl(materializer: ActorFlowMaterializer)
  extends MultiStreamInputProcessor(materializer.settings) {

  import akka.stream.impl.MultiStreamInputProcessor._

  val takeNextSubstream = TransferPhase(primaryInputs.NeedsInput && primaryOutputs.NeedsDemand) { () ⇒
    val Extract.Source(source) = primaryInputs.dequeueInputElement()
    val publisher = source.runWith(Sink.publisher)(materializer)
    // FIXME we can pass the flow to createSubstreamInput (but avoiding copy impl now)
    val inputs = createAndSubscribeSubstreamInput(publisher)
    nextPhase(streamSubstream(inputs))
  }

  def streamSubstream(substream: SubstreamInput): TransferPhase =
    TransferPhase(substream.NeedsInputOrComplete && primaryOutputs.NeedsDemand) { () ⇒
      if (substream.inputsDepleted) nextPhase(takeNextSubstream)
      else primaryOutputs.enqueueOutputElement(substream.dequeueInputElement())
    }

  initialPhase(1, takeNextSubstream)

  override def invalidateSubstreamInput(substream: SubstreamKey, e: Throwable): Unit = fail(e)

}
