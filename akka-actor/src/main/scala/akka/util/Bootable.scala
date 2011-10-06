/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.util
import akka.AkkaApplication

trait Bootable {
  def onLoad(application: AkkaApplication) {}
  def onUnload(application: AkkaApplication) {}
}
