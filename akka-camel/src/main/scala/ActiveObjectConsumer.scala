/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.camel

import java.util.concurrent.{BlockingQueue, ExecutorService, Executors, ThreadFactory, TimeUnit}

import kernel.util.Logging

import org.apache.camel.{AsyncCallback, AsyncProcessor, Consumer, Exchange, Processor}
import org.apache.camel.impl.ServiceSupport
import org.apache.camel.impl.converter.AsyncProcessorTypeConverter

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ActiveObjectConsumer(
    val endpoint: ActiveObjectEndpoint,
    proc: Processor,
    val activeObject: AnyRef)
  extends ServiceSupport with Consumer with Runnable with Logging {
  val processor = AsyncProcessorTypeConverter.convert(proc)
  println("------- creating consumer for: "+ endpoint.uri)

  override def run = {   
  }

  def doStart() = {
  }

  def doStop() = {
  }

  override def toString(): String = "ActiveObjectConsumer [" + endpoint.getEndpointUri + "]"
}
