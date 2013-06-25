/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import scala.util.control.NonFatal
import akka.actor._
import akka.routing.RandomRouter
import akka.io.SelectionHandler.WorkerForCommand
import akka.event.Logging

/**
 * Entry point to Akka’s IO layer.
 *
 * <b>All contents of the `akka.io` package is marked “experimental”.</b>
 *
 * This marker signifies that APIs may still change in response to user feedback
 * through-out the 2.2 release cycle. The implementation itself is considered
 * stable and ready for production use.
 *
 * @see <a href="http://doc.akka.io/">the Akka online documentation</a>
 */
object IO {

  trait Extension extends akka.actor.Extension {
    def manager: ActorRef
  }

  /**
   * Scala API: obtain a reference to the manager actor for the given IO extension,
   * for example [[Tcp]] or [[Udp]].
   *
   * For the Java API please refer to the individual extensions directly.
   */
  def apply[T <: Extension](key: ExtensionId[T])(implicit system: ActorSystem): ActorRef = key(system).manager

}