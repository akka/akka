//**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.camel

import org.apache.camel.impl.{DefaultCamelContext, DefaultEndpoint, DefaultComponent}
import se.scalablesolutions.akka.kernel.{Supervisor, Logging}
/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class SupervisorAwareCamelContext extends DefaultCamelContext with Logging {
  var supervisor: Supervisor = _
}