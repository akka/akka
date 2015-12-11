/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.io

import java.io.{ File, OutputStream }
import akka.stream.impl.SinkModule
import akka.stream.impl.StreamLayout.Module
import akka.stream.impl.Stages.DefaultAttributes.IODispatcher
import akka.stream.{ ActorMaterializer, MaterializationContext, Attributes, SinkShape }
import akka.stream.ActorAttributes.Dispatcher
import akka.util.ByteString
import scala.concurrent.{ Future, Promise }

/**
 * INTERNAL API
 * Creates simple synchronous (Java 6 compatible) Sink which writes all incoming elements to the given file
 * (creating it before hand if necessary).
 */
private[akka] final class FileSink(f: File, append: Boolean, val attributes: Attributes, shape: SinkShape[ByteString])
  extends SinkModule[ByteString, Future[Long]](shape) {

  override def create(context: MaterializationContext) = {
    val materializer = ActorMaterializer.downcast(context.materializer)
    val settings = materializer.effectiveSettings(context.effectiveAttributes)

    val bytesWrittenPromise = Promise[Long]()
    val props = FileSubscriber.props(f, bytesWrittenPromise, settings.maxInputBufferSize, append)
    val dispatcher = context.effectiveAttributes.get[Dispatcher](IODispatcher).dispatcher

    val ref = materializer.actorOf(context, props.withDispatcher(dispatcher))
    (akka.stream.actor.ActorSubscriber[ByteString](ref), bytesWrittenPromise.future)
  }

  override protected def newInstance(shape: SinkShape[ByteString]): SinkModule[ByteString, Future[Long]] =
    new FileSink(f, append, attributes, shape)

  override def withAttributes(attr: Attributes): Module =
    new FileSink(f, append, attr, amendShape(attr))
}

/**
 * INTERNAL API
 * Creates simple synchronous (Java 6 compatible) Sink which writes all incoming elements to the given file
 * (creating it before hand if necessary).
 */
private[akka] final class OutputStreamSink(createOutput: () ⇒ OutputStream, val attributes: Attributes, shape: SinkShape[ByteString])
  extends SinkModule[ByteString, Future[Long]](shape) {

  override def create(context: MaterializationContext) = {
    val materializer = ActorMaterializer.downcast(context.materializer)
    val settings = materializer.effectiveSettings(context.effectiveAttributes)
    val bytesWrittenPromise = Promise[Long]()

    val os = createOutput() // if it fails, we fail the materialization

    val props = OutputStreamSubscriber.props(os, bytesWrittenPromise, settings.maxInputBufferSize)

    val ref = materializer.actorOf(context, props)
    (akka.stream.actor.ActorSubscriber[ByteString](ref), bytesWrittenPromise.future)
  }

  override protected def newInstance(shape: SinkShape[ByteString]): SinkModule[ByteString, Future[Long]] =
    new OutputStreamSink(createOutput, attributes, shape)

  override def withAttributes(attr: Attributes): Module =
    new OutputStreamSink(createOutput, attr, amendShape(attr))
}

