/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl

import java.io.{ FileOutputStream, File }

object TestUtils {
  def writeAllText(text: String, file: File): Unit = {
    val fos = new FileOutputStream(file)
    try {
      fos.write(text.getBytes("UTF-8"))
    } finally fos.close()
  }
}
