/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl.common

import java.util.function.{ BiFunction, Function }

/**
 * Contains helpful methods to partially apply Functions for Java
 */
object PartialApplication {

  /**
   * It partially applies function A. In other words, it converts a 2 argument function to a 1 argument function by binding the first argument to {@code a}.
   * Here you can see an example:
   * <pre>
   * {@code BiFunction<Int, Int, Int> adder = (x, y) -> x + y;
   *   Function<Int, Int> add5 = bindParameter(adder, 5);
   *   add5(1);
   * }
   * </pre>
   * @param f the function to partially apply
   * @param a the first parameter to partially apply
   * @tparam A the type of the applied parameter
   * @tparam B the type of the second parameter
   * @tparam R the type of the return
   * @return the function partially applied
   */
  // @akka.annotation.ApiMayChange // FIXME use the real ones once Akka dependency bumped
  def bindParameter[A, B, R](f: BiFunction[A, B, R], a: A): Function[B, R] = {
    new Function[B, R] {
      override def apply(b: B): R = f.apply(a, b)
    }
  }

}
