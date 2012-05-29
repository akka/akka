/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package org.scalatest.akka

import org.scalatest.tools.StandardOutReporter
import org.scalatest.events._
import java.lang.Boolean.getBoolean

class QuietReporter(inColor: Boolean) extends StandardOutReporter(false, inColor, false, true) {
  def this() = this(!getBoolean("akka.test.nocolor"))

  override def apply(event: Event): Unit = event match {
    case _: RunStarting ⇒ ()
    case _              ⇒ super.apply(event)
  }

  override def makeFinalReport(resourceName: String, duration: Option[Long], summaryOption: Option[Summary]) {}
}
