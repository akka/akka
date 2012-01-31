/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.util

/**
 * Extractor of non-fatal Throwables. Will not match
 * VirtualMachineError (OutOfMemoryError, StackOverflowError)
 * and InterruptedException.
 *
 * Usage to catch all non-fatal throwables:
 * {{{
 *   try {
 *     // dangerous stuff
 *   } catch {
 *     case NonFatal(e) => log.error(e, "Something not that bad")
 *   }
 * }}}
 */
object NonFatal {

  def unapply(t: Throwable): Option[Throwable] = t match {
    // VirtualMachineError includes OutOfMemoryError, StackOverflowError and other fatal errors
    case e: VirtualMachineError  ⇒ None
    case e: ThreadDeath          ⇒ None
    case e: InterruptedException ⇒ None
    case e                       ⇒ Some(e)
  }

}

/**
 * Match InterruptedException and the same as
 * [[akka.util.NonFatal]].
 */
object NonFatalOrInterrupted {
  def unapply(t: Throwable): Option[Throwable] = t match {
    case e: InterruptedException ⇒ Some(e)
    case e                       ⇒ NonFatal.unapply(t)
  }
}