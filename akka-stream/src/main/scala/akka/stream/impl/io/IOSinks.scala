/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.io

import java.io.{ File, OutputStream }

import akka.stream.impl.SinkModule
import akka.stream.impl.StreamLayout.Module
import akka.stream.{ ActorFlowMaterializer, MaterializationContext, Attributes, SinkShape }
import akka.util.ByteString

import scala.concurrent.{ Future, Promise }

/**
 * INTERNAL API
 * Creates simple synchronous (Java 6 compatible) Sink which writes all incoming elements to the given file
 * (creating it before hand if neccessary).
 */
private[akka] final class SynchronousFileSink(f: File, append: Boolean, val attributes: Attributes, shape: SinkShape[ByteString])
  extends SinkModule[ByteString, Future[Long]](shape) {

  override def create(context: MaterializationContext) = {
    val mat = ActorFlowMaterializer.downcast(context.materializer)
    val settings = mat.effectiveSettings(context.effectiveAttributes)

    val bytesWrittenPromise = Promise[Long]()
    val props = SynchronousFileSubscriber.props(f, bytesWrittenPromise, settings.maxInputBufferSize, append)
    val dispatcher = IOSettings.fileIoDispatcher(context)

    val ref = mat.actorOf(context, props.withDispatcher(dispatcher))
    (akka.stream.actor.ActorSubscriber[ByteString](ref), bytesWrittenPromise.future)
  }

  override protected def newInstance(shape: SinkShape[ByteString]): SinkModule[ByteString, Future[Long]] =
    new SynchronousFileSink(f, append, attributes, shape)

  override def withAttributes(attr: Attributes): Module =
    new SynchronousFileSink(f, append, attr, amendShape(attr))
}

/**
 * INTERNAL API
 * Creates simple synchronous (Java 6 compatible) Sink which writes all incoming elements to the given file
 * (creating it before hand if neccessary).
 */
private[akka] final class OutputStreamSink(createOutput: () ⇒ OutputStream, val attributes: Attributes, shape: SinkShape[ByteString])
  extends SinkModule[ByteString, Future[Long]](shape) {

  override def create(context: MaterializationContext) = {
    val mat = ActorFlowMaterializer.downcast(context.materializer)
    val settings = mat.effectiveSettings(context.effectiveAttributes)
    val bytesWrittenPromise = Promise[Long]()

    val os = createOutput() // if it fails, we fail the materialization

    val props = OutputStreamSubscriber.props(os, bytesWrittenPromise, settings.maxInputBufferSize)

    val ref = mat.actorOf(context, props)
    (akka.stream.actor.ActorSubscriber[ByteString](ref), bytesWrittenPromise.future)
  }

  override protected def newInstance(shape: SinkShape[ByteString]): SinkModule[ByteString, Future[Long]] =
    new OutputStreamSink(createOutput, attributes, shape)

  override def withAttributes(attr: Attributes): Module =
    new OutputStreamSink(createOutput, attr, amendShape(attr))
}
