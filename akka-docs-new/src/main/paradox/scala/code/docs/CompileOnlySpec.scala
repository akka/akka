/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package docs

trait CompileOnlySpec {
  /**
   * Given a block of code... does NOT execute it.
   * Useful when writing code samples in tests, which should only be compiled.
   */
  def compileOnlySpec(body: => Unit) = ()
}
