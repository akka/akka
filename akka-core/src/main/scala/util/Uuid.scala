/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.util

/**
 * Factory object for very fast UUID generation.
 */
object UUID {
  def newUuid: Long = org.codehaus.aspectwerkz.proxy.Uuid.newUuid
}
