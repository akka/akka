/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl2

import akka.stream.impl.TransferPhase
import akka.stream.impl.MultiStreamInputProcessor
import akka.stream.scaladsl2.Source
import akka.stream.scaladsl2.FlowMaterializer

/**
 * INTERNAL API
 */
private[akka] class ConcatAllImpl(materializer: FlowMaterializer)
  extends MultiStreamInputProcessor(materializer.settings) {

  import MultiStreamInputProcessor._

  val takeNextSubstream = TransferPhase(primaryInputs.NeedsInput && primaryOutputs.NeedsDemand) { () ⇒
    val flow = primaryInputs.dequeueInputElement().asInstanceOf[Source[Any]]
    val publisher = flow.toPublisher()(materializer)
    // FIXME we can pass the flow to createSubstreamInput (but avoiding copy impl now)
    val inputs = createAndSubscribeSubstreamInput(publisher)
    nextPhase(streamSubstream(inputs))
  }

  def streamSubstream(substream: SubstreamInput): TransferPhase =
    TransferPhase(substream.NeedsInputOrComplete && primaryOutputs.NeedsDemand) { () ⇒
      if (substream.inputsDepleted) nextPhase(takeNextSubstream)
      else primaryOutputs.enqueueOutputElement(substream.dequeueInputElement())
    }

  nextPhase(takeNextSubstream)

  override def invalidateSubstreamInput(substream: SubstreamKey, e: Throwable): Unit = fail(e)
}
