/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.japi.pf;

import static akka.actor.SupervisorStrategy.Directive;

/**
 * Used for building a partial function for {@link akka.actor.Actor#supervisorStrategy() Actor.supervisorStrategy()}.
 * *
 * Inside an actor you can use it like this with Java 8 to define your supervisorStrategy.
 * <p/>
 * Example:
 * <pre>
 * @Override
 * private static SupervisorStrategy strategy =
 *   new OneForOneStrategy(10, Duration.create("1 minute"), DeciderBuilder.
 *     match(ArithmeticException.class, e -> resume()).
 *     match(NullPointerException.class, e -> restart()).
 *     match(IllegalArgumentException.class, e -> stop()).
 *     matchAny(o -> escalate()).build());
 *
 * @Override
 * public SupervisorStrategy supervisorStrategy() {
 *   return strategy;
 * }
 * </pre>
 *
 * This is an EXPERIMENTAL feature and is subject to change until it has received more real world testing.
 */
public class DeciderBuilder {
  private DeciderBuilder() {
  }

  /**
   * Return a new {@link PFBuilder} with a case statement added.
   *
   * @param type   a type to match the argument against
   * @param apply  an action to apply to the argument if the type matches
   * @return       a builder with the case statement added
   */
  public static <P extends Throwable> PFBuilder<Throwable, Directive> match(final Class<P> type, FI.Apply<P, Directive> apply) {
    return Match.match(type, apply);
  }

  /**
   * Return a new {@link PFBuilder} with a case statement added.
   *
   * @param type       a type to match the argument against
   * @param predicate  a predicate that will be evaluated on the argument if the type matches
   * @param apply      an action to apply to the argument if the type matches and the predicate returns true
   * @return           a builder with the case statement added
   */
  public static <P extends Throwable> PFBuilder<Throwable, Directive> match(final Class<P> type,
                                                FI.TypedPredicate<P> predicate,
                                                FI.Apply<P, Directive> apply) {
    return Match.match(type, predicate, apply);
  }

  /**
   * Return a new {@link PFBuilder} with a case statement added.
   *
   * @param apply      an action to apply to the argument
   * @return           a builder with the case statement added
   */
  public static PFBuilder<Throwable, Directive> matchAny(FI.Apply<Object, Directive> apply) {
    return Match.matchAny(apply);
  }
}
