/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package org.scalatest.extra

import org.scalatest.tools.StandardOutReporter
import org.scalatest.events._
import java.lang.Boolean.getBoolean

class QuietReporter(inColor: Boolean, withDurations: Boolean = false) extends StandardOutReporter(withDurations, inColor, false, true, false, false, false, false, false) {

  def this() = this(!getBoolean("akka.test.nocolor"), !getBoolean("akka.test.nodurations"))

  override def apply(event: Event): Unit = event match {
    case _: RunStarting ⇒ ()
    case _              ⇒ super.apply(event)
  }
}
