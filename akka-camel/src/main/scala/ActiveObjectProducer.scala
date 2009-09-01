/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.camel

import java.util.Collection
import kernel.util.Logging;
import java.util.concurrent.BlockingQueue;

import org.apache.camel.{Exchange, AsyncProcessor, AsyncCallback}
import org.apache.camel.impl.DefaultProducer

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ActiveObjectProducer(
    val endpoint: ActiveObjectEndpoint,
    val activeObject: MessageDriven)
    extends DefaultProducer(endpoint) with AsyncProcessor with Logging {
  private val actorName = endpoint.activeObjectName
    
  def process(exchange: Exchange) = activeObject.onMessage(exchange) // FIXME: should we not invoke the generic server here?

  def process(exchange: Exchange, callback: AsyncCallback): Boolean = {
    val copy = exchange.copy
    copy.setProperty("CamelAsyncCallback", callback)
    activeObject.onMessage(copy)
    callback.done(true)
    true
  }

  override def doStart = {
    super.doStart
  }

  override def doStop = {
    super.doStop
  }

  override def toString(): String = "ActiveObjectProducer [" + endpoint.getEndpointUri + "]"
}
