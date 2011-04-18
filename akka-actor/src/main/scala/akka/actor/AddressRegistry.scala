/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import java.net.InetSocketAddress

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object AddressRegistry {

  def isLocal(address: String): Boolean = {
    true
  }

  def lookupRemoteAddress(address: String): Option[InetSocketAddress] = {
    None
  }
}
