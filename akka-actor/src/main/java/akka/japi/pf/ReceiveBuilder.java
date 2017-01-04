/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.japi.pf;

/**
 * Used for building a partial function for {@link akka.actor.Actor#receive() Actor.receive()}.
 *
 * There is both a match on type only, and a match on type and predicate.
 *
 * Inside an actor you can use it like this with Java 8 to define your receive method.
 * <p>
 * Example:
 * </p>
 * <pre>
 * &#64;Override
 * public Actor() {
 *   receive(ReceiveBuilder.
 *     match(Double.class, d -&gt; {
 *       sender().tell(d.isNaN() ? 0 : d, self());
 *     }).
 *     match(Integer.class, i -&gt; {
 *       sender().tell(i * 10, self());
 *     }).
 *     match(String.class, s -&gt; s.startsWith("foo"), s -&gt; {
 *       sender().tell(s.toUpperCase(), self());
 *     }).build()
 *   );
 * }
 * </pre>
 *
 * This is an EXPERIMENTAL feature and is subject to change until it has received more real world testing.
 */
public class ReceiveBuilder {
  private ReceiveBuilder() {
  }

  /**
   * Return a new {@link UnitPFBuilder} with no case statements. They can be added later as the returned {@link
   * UnitPFBuilder} is a mutable object.
   *
   * @return a builder with no case statements
   */
  public static UnitPFBuilder<Object> create() {
    return new UnitPFBuilder<>();
  }

  /**
   * Return a new {@link UnitPFBuilder} with a case statement added.
   *
   * @param type  a type to match the argument against
   * @param apply an action to apply to the argument if the type matches
   * @return a builder with the case statement added
   */
  public static <P> UnitPFBuilder<Object> match(Class<P> type,
                                                FI.UnitApply<? super P> apply) {
    return UnitMatch.match(type, apply);
  }

  /**
   * Return a new {@link UnitPFBuilder} with a case statement added.
   *
   * @param type      a type to match the argument against
   * @param predicate a predicate that will be evaluated on the argument if the type matches
   * @param apply     an action to apply to the argument if the type matches and the predicate returns true
   * @return a builder with the case statement added
   */
  public static <P> UnitPFBuilder<Object> match(Class<P> type,
                                                FI.TypedPredicate<? super P> predicate,
                                                FI.UnitApply<? super P> apply) {
    return UnitMatch.match(type, predicate, apply);
  }

  /**
   * Return a new {@link UnitPFBuilder} with a case statement added.
   *
   * @param predicate a predicate that will be evaluated on the argument if the type matches
   * @param apply     an action to apply to the argument if the type matches and the predicate returns true
   * @return a builder with the case statement added
   */
  public static UnitPFBuilder<Object> match(FI.TypedPredicate<Object> predicate,
                                            FI.UnitApply<Object> apply) {
    return UnitMatch.match(predicate, apply);
  }
  
  /**
   * Return a new {@link UnitPFBuilder} with a case statement added.
   *
   * Note: This can't be safely generic in P, since some object.equals(a) does not imply that [a] is an instance of
   * the same type that [object] was compile-time.
   * If you want that safety, use UnitMatch.matchEquals directly (to get a UnitPFBuilder<T>), or use 
   * the matchEquals variant that takes a Class argument as well.
   * 
   * @param object the object to compare equals with
   * @param apply  an action to apply to the argument if the object compares equal
   * @return a builder with the case statement added
   */
  public static UnitPFBuilder<Object> matchEquals(Object object, FI.UnitApply<Object> apply) {
    return UnitMatch.matchEquals(object, apply);
  }

  /**
   * Return a new {@link UnitPFBuilder} with a case statement added.
   *
   * Note: This can't be safely generic in P, since some object.equals(a) does not imply that [a] is an instance of
   * the same type that [object] was compile-time.
   * If you want that safety, use UnitMatch.matchEquals directly (to get a UnitPFBuilder<T>), or use 
   * the matchEquals variant that takes a Class argument as well.
   * 
   * @param object    the object to compare equals with
   * @param predicate a predicate that will be evaluated on the argument if the object compares equal
   * @param apply     an action to apply to the argument if the object compares equal
   * @return a builder with the case statement added
   */
  public static UnitPFBuilder<Object> matchEquals(Object object, 
                                                  FI.TypedPredicate<Object> predicate, 
                                                  FI.UnitApply<Object> apply) {
    return UnitMatch.matchEquals(object, predicate, apply);
  }

  /**
   * Return a new {@link UnitPFBuilder} with a case statement added.
   *
   * @param apply an action to apply to the argument
   * @return a builder with the case statement added
   */
  public static UnitPFBuilder<Object> matchAny(FI.UnitApply<Object> apply) {
    return UnitMatch.matchAny(apply);
  }

}
