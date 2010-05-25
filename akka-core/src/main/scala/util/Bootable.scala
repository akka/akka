/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.util

trait Bootable {
  def onLoad {}
  def onUnload {}
}
