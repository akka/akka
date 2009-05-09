/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.camel

import config.ActiveObjectGuiceConfigurator
import se.scalablesolutions.akka.kernel.Logging

import java.util.{ArrayList, HashSet, List, Set}
import java.util.concurrent.{BlockingQueue, CopyOnWriteArraySet, LinkedBlockingQueue}

import org.apache.camel.{Component, Consumer, Exchange, Processor, Producer}
import org.apache.camel.impl.{DefaultEndpoint, DefaultComponent};
import org.apache.camel.spi.BrowsableEndpoint;

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ActiveObjectEndpoint(val uri: String, val component: DefaultComponent, val conf: ActiveObjectGuiceConfigurator) // FIXME: need abstraction trait here
  extends DefaultEndpoint(uri) with BrowsableEndpoint with Logging {

  val firstSep = uri.indexOf(':')
  val lastSep = uri.lastIndexOf(  '.')

  val scheme = uri.substring(0, firstSep)
  val activeObjectName = uri.substring(uri.indexOf(':') + 1, lastSep)
  val methodName = uri.substring(lastSep + 1, uri.length) 
  val activeObject = conf.getActiveObject(activeObjectName).asInstanceOf[MessageDriven]
//  val activeObjectProxy = conf.getActiveObjectProxy(activeObjectName)
  
//  val genericServer = supervisor.getServerOrElse(
//    activeObjectName,
//    throw new IllegalArgumentException("Can't find active object with name [" + activeObjectName + "] and method [" + methodName + "]"))

  log.debug("Creating Camel Endpoint for scheme [%s] and component [%s]", scheme, activeObjectName)

  private var queue: BlockingQueue[Exchange] = new LinkedBlockingQueue[Exchange](1000)

  override def createProducer: Producer = new ActiveObjectProducer(this, activeObject)

  override def createConsumer(processor: Processor): Consumer = new ActiveObjectConsumer(this, processor, activeObject)
  
  override def getExchanges: List[Exchange] = new ArrayList[Exchange](queue)
  
  override def isSingleton = true
} 
