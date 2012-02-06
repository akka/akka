/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.util

/**
 * Extractor of non-fatal Throwables. Will not match fatal errors
 * like VirtualMachineError (OutOfMemoryError, StackOverflowError)
 * ThreadDeath, and InterruptedException.
 *
 * Usage to catch all harmless throwables:
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
    case _: VirtualMachineError | _: ThreadDeath | _: InterruptedException ⇒ None
    case e ⇒ Some(e)
  }

}

