/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.camel

import config.ActiveObjectConfigurator

import java.util.Map
import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue}

import org.apache.camel.{Endpoint, Exchange}
import org.apache.camel.impl.DefaultComponent

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ActiveObjectComponent(val conf: ActiveObjectConfigurator) extends DefaultComponent {
  override def createEndpoint(uri: String, remaining: String, parameters: Map[_,_]): Endpoint = {
    //val consumers = getAndRemoveParameter(parameters, "concurrentConsumers", classOf[Int], 1)
    new ActiveObjectEndpoint(uri, this, conf)
  }
}
