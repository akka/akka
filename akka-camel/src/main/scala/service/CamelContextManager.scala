/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.camel.service

import org.apache.camel.CamelContext
import org.apache.camel.impl.DefaultCamelContext

/**
 * Manages the CamelContext used by CamelService.
 *
 * @author Martin Krasser
 */
object CamelContextManager {

  /**
   * The CamelContext used by CamelService. Can be modified by applications prior to
   * loading the CamelService.
   */
  var context: CamelContext = new DefaultCamelContext

}