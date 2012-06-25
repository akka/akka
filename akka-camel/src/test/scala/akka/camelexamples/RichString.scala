/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camelexamples

import language.implicitConversions

import java.io.FileWriter

private[camelexamples] object RichString {
  implicit def toRichString(s: String): RichString = new RichString(s)
}

private[camelexamples] class RichString(s: String) {
  def saveAs(fileName: String) = write(fileName, s)
  def >>(fileName: String) = this.saveAs(fileName)
  def <<(content: String) = write(s, content)

  private[this] def write(fileName: String, content: String) {
    val f = new FileWriter(fileName)
    f.write(content)
    f.close()
  }
}

