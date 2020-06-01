/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery.tcp.ssl

/**
 *
 */
class FileTools {}

import java.io.File
import java.nio.file.Files

import akka.actor.Actor
import akka.actor.Props

import scala.util.control.NonFatal

object FileSystemObserver {
  def props: Props = Props(new FileSystemObserver)
  case class CanRead(absolutePath: String)
  case object FileRead
}

class FileSystemObserver extends Actor {
  import FileSystemObserver._
  override def receive = {
    case CanRead(absolutePath) => {
      var success = false
      var attempts = 5
      while (!success && attempts > 0) {
        success = try {
          attempts -= 1
          Thread.sleep(500)
          Files.readAllBytes(new File(absolutePath).toPath)
          println(s"${context.system.name} looking for $absolutePath - FOUND")
          true
        } catch {
          case NonFatal(_) =>
            false
        }
      }
      sender ! FileRead
    }
  }
}
