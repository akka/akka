/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.japi.pf;

/**
 * A builder for {@link scala.PartialFunction}.
 *
 * @param <I> the input type, that this PartialFunction will be applied to
 * @param <R> the return type, that the results of the application will have
 */
public final class PFBuilder<I, R> extends AbstractPFBuilder<I, R> {

  /**
   * Create a PFBuilder.
   */
  public PFBuilder() {
  }

  /**
   * Add a new case statement to this builder.
   *
   * @param type   a type to match the argument against
   * @param apply  an action to apply to the argument if the type matches
   * @return       a builder with the case statement added
   */
  public <P> PFBuilder<I, R> match(final Class<P> type, FI.Apply<P, R> apply) {
    addStatement(new CaseStatement<I, P, R>(
      new FI.Predicate() {
        @Override
        public boolean defined(Object o) {
          return type.isInstance(o);
        }
      }, apply));
    return this;
  }

  /**
   * Add a new case statement to this builder.
   *
   * @param type       a type to match the argument against
   * @param predicate  a predicate that will be evaluated on the argument if the type matches
   * @param apply      an action to apply to the argument if the type matches and the predicate returns true
   * @return           a builder with the case statement added
   */
  public <P> PFBuilder<I, R> match(final Class<P> type,
                                   final FI.TypedPredicate<P> predicate,
                                   final FI.Apply<P, R> apply) {
    addStatement(new CaseStatement<I, P, R>(
      new FI.Predicate() {
        @Override
        public boolean defined(Object o) {
          if (!type.isInstance(o))
            return false;
          else {
            @SuppressWarnings("unchecked")
            P p = (P) o;
            return predicate.defined(p);
          }
        }
      }, apply));
    return this;
  }

  /**
   * Add a new case statement to this builder.
   *
   * @param object  the object to compare equals with
   * @param apply  an action to apply to the argument if the object compares equal
   * @return a builder with the case statement added
   */
  public <P> PFBuilder<I, R> matchEquals(final P object,
                                         final FI.Apply<P, R> apply) {
    addStatement(new CaseStatement<I, P, R>(
      new FI.Predicate() {
        @Override
        public boolean defined(Object o) {
          return object.equals(o);
        }
      }, apply));
    return this;
  }

  /**
   * Add a new case statement to this builder, that matches any argument.
   * @param apply  an action to apply to the argument
   * @return       a builder with the case statement added
   */
  public PFBuilder<I, R> matchAny(final FI.Apply<Object, R> apply) {
    addStatement(new CaseStatement<I, Object, R>(
      new FI.Predicate() {
        @Override
        public boolean defined(Object o) {
          return true;
        }
      }, apply));
    return this;
  }
}
