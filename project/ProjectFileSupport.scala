/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import java.io.{File => JFile}

trait ProjectFileSupport {

  protected def getPackageName(fileName: String): Option[String] = {
    def getPackageName0(fileType: String): String = {
      fileName.split(JFile.separatorChar)
        .dropWhile(part ⇒ part != fileType)
        .drop(1)
        .dropRight(1)
        .mkString(".")
    }

    fileName.split('.').lastOption match {
      case Some(fileType) ⇒
        fileType match {
          case "java" ⇒
            Option(getPackageName0("java"))
          case "scala" ⇒
            Option(getPackageName0("scala"))
          case _ ⇒ None
        }
      case None ⇒ None
    }
  }
}
