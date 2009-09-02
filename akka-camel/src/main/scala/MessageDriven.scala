/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.camel

import org.apache.camel.Exchange

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait MessageDriven {
  def onMessage(exchange: Exchange)
}