/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.util

trait Bootable {
  def onLoad : Unit = ()
  def onUnload : Unit = ()
}