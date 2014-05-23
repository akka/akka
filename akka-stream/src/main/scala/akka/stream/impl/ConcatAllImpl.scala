/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.stream.MaterializerSettings
import org.reactivestreams.api.Producer
import akka.stream.impl.MultiStreamInputProcessor.SubstreamKey

/**
 * INTERNAL API
 */
private[akka] class ConcatAllImpl(_settings: MaterializerSettings) extends MultiStreamInputProcessor(_settings) {

  val takeNextSubstream = TransferPhase(primaryInputs.NeedsInput && primaryOutputs.NeedsDemand) { () ⇒
    val producer = primaryInputs.dequeueInputElement().asInstanceOf[Producer[Any]]
    val inputs = createSubstreamInputs(producer)
    nextPhase(streamSubstream(inputs))
  }

  def streamSubstream(substream: SubstreamInputs): TransferPhase =
    TransferPhase(substream.NeedsInputOrComplete && primaryOutputs.NeedsDemand) { () ⇒
      if (substream.inputsDepleted) nextPhase(takeNextSubstream)
      else primaryOutputs.enqueueOutputElement(substream.dequeueInputElement())
    }

  nextPhase(takeNextSubstream)

  override def invalidateSubstream(substream: SubstreamKey, e: Throwable): Unit = fail(e)
}
