/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.japi.pf;


/**
 * A builder for {@link scala.PartialFunction}.
 *
 * @param <A> the input type, that this PartialFunction will be applied to
 * @param <B> the return type, that the results of the application will have
 *
 * This is an EXPERIMENTAL feature and is subject to change until it has received more real world testing.
 */
public final class PFBuilder<A, B> extends AbstractPFBuilder<A, B> {

  /**
   * Create a PFBuilder.
   */
  public PFBuilder() {
  }

  /**
   * Add a new case statement to this builder.
   *
   * @param type  a type to match the argument against
   * @param apply an action to apply to the argument if the type matches
   * @return a builder with the case statement added
   */
  public <P extends A> PFBuilder<A, B> match(
          Class<P> type, 
          FI.Apply<? super P, ? extends B> apply) {
    addStatement(type::isInstance, i -> apply.apply(type.cast(i)));
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
  public <P extends A> PFBuilder<A, B> match(
          Class<P> type,
          FI.TypedPredicate<? super P> predicate,
          FI.Apply<? super P, ? extends B> apply) {
    addStatement(i -> type.isInstance(i) && predicate.defined(type.cast(i)), i -> apply.apply(type.cast(i)));
    return this;
  }

  /**
   * Add a new case statement to this builder.
   *
   * This variant will at runtime check the type of [predicate], using reflection to infer the actual
   * type argument, and only allow instances of that type to reach the predicate. This only works one-level,
   * e.g. for a TypedPredicate<List<String>> we can only filter that instances of List go in, not what's
   * in the list. This is because although generics are preserved for the method's type signature, they're
   * not available for actual object instances passed into the method.
   * 
   * @param predicate a predicate that will be evaluated on the argument if the type matches
   * @param apply     an action to apply to the argument if the type matches and the predicate returns true
   * @return a builder with the case statement added
   */
  public <P extends A> PFBuilder<A, B> match(
          FI.TypedPredicate<P> predicate,
          FI.Apply<P, ? extends B> apply) {
    
    @SuppressWarnings("unchecked")
    Class<P> type = (Class<P>) Reflect.resolveLambdaArgumentType(predicate, 0);
      
    addStatement(i -> type.isInstance(i) && predicate.defined(type.cast(i)), i -> apply.apply(type.cast(i)));
    return this;
  }

/**
   * Add a new case statement to this builder.
   *
   * @param object the object to compare equals with
   * @param apply  an action to apply to the argument if the object compares equal
   * @return a builder with the case statement added
   */
  public PFBuilder<A, B> matchEquals(A object, FI.Apply<? super A, ? extends B> apply) {
    addStatement(object::equals, apply::apply);
    return this;
  }

  /**
   * Add a new case statement to this builder.
   *
   * @param object the object to compare equals with
   * @param predicate a predicate that will be evaluated on the argument if the object compares equal
   * @param apply  an action to apply to the argument if the object compares equal
   * @return a builder with the case statement added
   */
  public PFBuilder<A, B> matchEquals(
          A object, 
          FI.TypedPredicate<? super A> predicate, 
          FI.Apply<? super A, ? extends B> apply) {
    addStatement(a -> object.equals(a) && predicate.defined(a), apply::apply);
    return this;
  }

  /**
   * Add a new case statement to this builder, that matches any argument.
   *
   * @param apply an action to apply to the argument
   * @return a builder with the case statement added
   */
  public PFBuilder<A, B> matchAny(final FI.Apply<? super A, ? extends  B> apply) {
    addStatement(i -> true, apply::apply);
    return this;
  }
}
