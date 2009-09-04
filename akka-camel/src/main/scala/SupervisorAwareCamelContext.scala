/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.camel

import actor.Supervisor
import util.Logging
import org.apache.camel.impl.{DefaultCamelContext, DefaultEndpoint, DefaultComponent}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class SupervisorAwareCamelContext extends DefaultCamelContext with Logging {
  var supervisor: Supervisor = _
}