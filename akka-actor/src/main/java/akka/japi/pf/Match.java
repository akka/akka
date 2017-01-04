/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.japi.pf;

import scala.MatchError;
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
public class Match<A, B> extends AbstractMatch<A, B> {
  public static <A,B> PFBuilder<A,B> newBuilder() {
      return new PFBuilder<>();
  }
    

  /**
   * Convenience function to create a {@link PFBuilder} with the first
   * case statement added.
   *
   * @param type  a type to match the argument against
   * @param apply an action to apply to the argument if the type matches
   * @return a builder with the case statement added
   * @see PFBuilder#match(Class, FI.Apply)
   */
  public static <A, B, P extends A> PFBuilder<A, B> match(
          Class<P> type, 
          FI.Apply<? super P, ? extends B> apply) {
    return new PFBuilder<A, B>().match(type, apply);
  }
  
  /**
   * Convenience function to create a {@link PFBuilder} with the first
   * case statement added.
   *
   * This variant will at runtime check the type of [apply], using reflection to infer the actual
   * type argument, and only allow instances of that type to reach the predicate. This only works one-level,
   * e.g. for a TypedPredicate<List<String>> we can only filter that instances of List go in, not what's
   * in the list. This is because although generics are preserved for the method's type signature, they're
   * not available for actual object instances passed into the method.
   * 
   * @param apply     an action to apply to the argument if the type matches and the predicate returns true
   * @return a builder with the case statement added
   * @see PFBuilder#match(FI.Apply)
   */
  public static <A, B, P extends A> PFBuilder<A, B> match(
          FI.Apply<? super P, ? extends B> apply) {
    
    @SuppressWarnings("unchecked")
    Class<P> type = (Class<P>) Reflect.resolveLambdaArgumentType(apply, 0);
      
    return new PFBuilder<A, B>().match(type, apply);
  }
  
  /**
   * Convenience function to create a {@link PFBuilder} with the first
   * case statement added.
   *
   * @param type      a type to match the argument against
   * @param predicate a predicate that will be evaluated on the argument if the type matches
   * @param apply     an action to apply to the argument if the type matches
   * @return a builder with the case statement added
   * @see PFBuilder#match(Class, FI.TypedPredicate, FI.Apply)
   */
  public static <A, B, P extends A> PFBuilder<A, B> match(
          Class<P> type,
          FI.TypedPredicate<? super P> predicate,
          FI.Apply<? super P, ? extends B> apply) {
    return new PFBuilder<A, B>().match(type, predicate, apply);
  }

  /**
   * Convenience function to create a {@link PFBuilder} with the first
   * case statement added.
   *
   * @param predicate a predicate that will be evaluated on the argument if the type matches
   * @param apply     an action to apply to the argument if the type matches
   * @return a builder with the case statement added
   * @see PFBuilder#match(FI.TypedPredicate, FI.Apply)
   */
  public static <A, B> PFBuilder<A, B> match(
          FI.TypedPredicate<A> predicate,
          FI.Apply<A, ? extends B> apply) {
      return new PFBuilder<A, B>().match(predicate, apply);
  }
  
  /**
   * Convenience function to create a {@link PFBuilder} with the first
   * case statement added.
   *
   * @param object the object to compare equals with
   * @param apply  an action to apply to the argument if the object compares equal
   * @return a builder with the case statement added
   * @see PFBuilder#matchEquals(Object, FI.Apply)
   */
  public static <A, B> PFBuilder<A, B> matchEquals(A object, FI.Apply<? super A, ? extends B> apply) {
    return new PFBuilder<A, B>().matchEquals(object, apply);
  }

  /**
   * Convenience function to create a {@link PFBuilder} with the first
   * case statement added.
   *
   * @param apply an action to apply to the argument
   * @return a builder with the case statement added
   * @see PFBuilder#matchAny(FI.Apply)
   */
  public static <A, B> PFBuilder<A,B> matchAny(final FI.Apply<? super A, ? extends  B> apply) {
    return new PFBuilder<A, B>().matchAny(apply);
  }

  /**
   * Create a {@link Match} from the builder.
   *
   * @param builder a builder representing the partial function
   * @return a {@link Match} that can be reused
   */
  public static final <F, T> Match<F, T> create(PFBuilder<F, T> builder) {
    return new Match<F, T>(builder.build());
  }

  Match(PartialFunction<A, B> statements) {
    super(statements);
  }

  /**
   * Convenience function to make the Java code more readable.
   * <p></p>
   * 
   * <pre><code>
   *   Matcher&lt;X, Y&gt; matcher = Matcher.create(...);
   * 
   *   Y someY = matcher.match(obj);
   * </code></pre>
   *
   * @param i the argument to apply the match to
   * @return the result of the application
   * @throws MatchError if there is no match
   */
  public B match(A i) throws MatchError {
    return statements.apply(i);
  }
}
