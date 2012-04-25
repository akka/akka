/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.util

/**
 * Extractor of non-fatal Throwables. Will not match fatal errors
 * like VirtualMachineError (OutOfMemoryError)
 * ThreadDeath, LinkageError and InterruptedException.
 * StackOverflowError is matched, i.e. considered non-fatal.
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

  def unapply(t: Throwable): Option[Throwable] = if (isFatal(t)) None else Some(t)

  private def isFatal(t: Throwable): Boolean =
    (t.isInstanceOf[VirtualMachineError] && !t.isInstanceOf[StackOverflowError]) || t.isInstanceOf[ThreadDeath] || t.isInstanceOf[InterruptedException] || t.isInstanceOf[LinkageError]
}

