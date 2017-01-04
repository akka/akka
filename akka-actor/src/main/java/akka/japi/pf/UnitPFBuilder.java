/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.japi.pf;

import scala.runtime.BoxedUnit;

/**
 * A builder for {@link scala.PartialFunction}.
 * This is a specialized version of {@link PFBuilder} to map java
 * void methods to {@link scala.runtime.BoxedUnit}.
 *
 * @param <A> the input type, that this PartialFunction to be applied to
 *
 * This is an EXPERIMENTAL feature and is subject to change until it has received more real world testing.
 */
public final class UnitPFBuilder<A> extends AbstractPFBuilder<A, BoxedUnit> {

  /**
   * Create a UnitPFBuilder.
   */
  public UnitPFBuilder() {
  }

  /**
   * Add a new case statement to this builder.
   *
   * @param type  a type to match the argument against
   * @param apply an action to apply to the argument if the type matches
   * @return a builder with the case statement added
   */
  public <P extends A> UnitPFBuilder<A> match(
          Class<P> type,
          FI.UnitApply<? super P> apply) {
    addUnitStatement(type::isInstance, i -> apply.apply(type.cast(i)));
    return this;
  }

  /**
   * Add a new case statement to this builder.
   *
   * @param type      a type to match the argument against
   * @param predicate a predicate that will be evaluated on the argument if the type matches
   * @param apply     an action to apply to the argument if the type matches and the predicate returns true
   * @return a builder with the case statement added
   */
  public <P extends A> UnitPFBuilder<A> match(
          Class<P> type,
          FI.TypedPredicate<? super P> predicate,
          FI.UnitApply<? super P> apply) {
    addUnitStatement(i -> type.isInstance(i) && predicate.defined(type.cast(i)), i -> apply.apply(type.cast(i)));
    return this;
  }

  /**
   * Add a new case statement to this builder.
   *
   * @param predicate a predicate that will be evaluated on the argument if the type matches
   * @param apply     an action to apply to the argument if the type matches and the predicate returns true
   * @return a builder with the case statement added
   */
  public UnitPFBuilder<A> match(
          FI.TypedPredicate<? super A> predicate,
          FI.UnitApply<? super A> apply) {
    addUnitStatement(predicate::defined, apply::apply);
    return this;
  }
  
  /**
   * Add a new case statement to this builder.
   *
   * @param object the object to compare equals with
   * @param apply  an action to apply to the argument if the object compares equal
   * @return a builder with the case statement added
   */
  public UnitPFBuilder<A> matchEquals(A object, FI.UnitApply<? super A> apply) {
      addUnitStatement(object::equals, apply::apply);
      return this;
    }

  /**
   * Add a new case statement to this builder.
   *
   * @param object    the object to compare equals with
   * @param predicate a predicate that will be evaluated on the argument if the object compares equal
   * @param apply     an action to apply to the argument if the object compares equal
   * @return a builder with the case statement added
   */
  public UnitPFBuilder<A> matchEquals(
          A object, 
          FI.TypedPredicate<? super A> predicate, 
          FI.UnitApply<? super A> apply) {
    addUnitStatement(a -> object.equals(a) && predicate.defined(a), apply::apply);
    return this;
  }

  /**
   * Add a new case statement to this builder, that matches any argument.
   *
   * @param apply an action to apply to the argument
   * @return a builder with the case statement added
   */
  public UnitPFBuilder<A> matchAny(final FI.UnitApply<? super A> apply) {
      addUnitStatement(i -> true, apply::apply);
      return this;
    }
}
