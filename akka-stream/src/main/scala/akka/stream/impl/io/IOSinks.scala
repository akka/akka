/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl.io

import java.io.OutputStream
import akka.annotation.InternalApi
import akka.stream.ActorAttributes.Dispatcher
import akka.stream._
import akka.stream.impl.SinkModule
import akka.util.ByteString

import scala.concurrent.{ Future, Promise }

/**
 * INTERNAL API
 * Creates simple synchronous Sink which writes all incoming elements to the output stream.
 */
@InternalApi private[akka] final class OutputStreamSink(
    createOutput: () => OutputStream,
    val attributes: Attributes,
    shape: SinkShape[ByteString],
    autoFlush: Boolean)
    extends SinkModule[ByteString, Future[IOResult]](shape) {

  override def create(context: MaterializationContext) = {
    val materializer = ActorMaterializerHelper.downcast(context.materializer)
    val ioResultPromise = Promise[IOResult]()

    val os = createOutput() // if it fails, we fail the materialization

    val maxInputBufferSize = context.effectiveAttributes.mandatoryAttribute[Attributes.InputBuffer].max

    val props = OutputStreamSubscriber
      .props(os, ioResultPromise, maxInputBufferSize, autoFlush)
      .withDispatcher(context.effectiveAttributes.mandatoryAttribute[Dispatcher].dispatcher)

    val ref = materializer.actorOf(context, props)
    (akka.stream.actor.ActorSubscriber[ByteString](ref), ioResultPromise.future)
  }

  override protected def newInstance(shape: SinkShape[ByteString]): SinkModule[ByteString, Future[IOResult]] =
    new OutputStreamSink(createOutput, attributes, shape, autoFlush)

  override def withAttributes(attr: Attributes): SinkModule[ByteString, Future[IOResult]] =
    new OutputStreamSink(createOutput, attr, amendShape(attr), autoFlush)
}
