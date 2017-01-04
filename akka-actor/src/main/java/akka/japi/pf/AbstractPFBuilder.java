/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.japi.pf;

import scala.PartialFunction;

/**
 * A builder for {@link scala.PartialFunction}.
 *
 * @param <A> the input type, that this PartialFunction will be applied to
 * @param <B> the return type, that the results of the application will have
 *
 * This is an EXPERIMENTAL feature and is subject to change until it has received more real world testing.
 */
abstract class AbstractPFBuilder<A, B> {

  protected PartialFunction<A, B> statements = null;

  protected void addStatement(FI.TypedPredicate<? super A> predicate, FI.Apply<? super A, ? extends B> apply) {
    if (statements == null) {
      statements = new CaseStatement<A,B>(predicate, apply);
    }
    else
      statements = statements.orElse(new CaseStatement<A,B>(predicate, apply));
  }

  protected void addUnitStatement(FI.TypedPredicate<? super A> predicate, FI.UnitApply<? super A> apply) {
      if (statements == null) {
        statements = new CaseStatement<A,B>(predicate, a -> { apply.apply(a); return null; });
      }
      else
        statements = statements.orElse(new CaseStatement<A,B>(predicate, a -> { apply.apply(a); return null; }));
    }

  /**
   * Build a {@link scala.PartialFunction} from this builder.
   * After this call the builder will be reset.
   *
   * @return  a PartialFunction for this builder.
   */
  public PartialFunction<A, B> build() {
    PartialFunction<A, B> empty = CaseStatement.empty();
    PartialFunction<A, B> statements = this.statements;

    this.statements = null;
    if (statements == null)
      return empty;
    else
      return statements.orElse(empty);
  }
}
