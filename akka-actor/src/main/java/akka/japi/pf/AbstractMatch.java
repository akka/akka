/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.japi.pf;

import scala.PartialFunction;

/**
 * Version of {@link scala.PartialFunction} that can be built during
 * runtime from Java.
 *
 * @param <A> the input type, that this PartialFunction will be applied to
 * @param <B> the return type, that the results of the application will have
 *
 * This is an EXPERIMENTAL feature and is subject to change until it has received more real world testing.
 */
class AbstractMatch<A, B> {

  protected final PartialFunction<A, B> statements;

  AbstractMatch(PartialFunction<A, B> statements) {
    PartialFunction<A, B> empty = CaseStatement.empty();
    if (statements == null)
      this.statements = empty;
    else
      this.statements = statements.orElse(empty);
  }

  /**
   * Turn this {@link Match} into a {@link scala.PartialFunction}.
   *
   * @return  a partial function representation ot his {@link Match}
   */
  public PartialFunction<A, B> asPF() {
    return statements;
  }
}
