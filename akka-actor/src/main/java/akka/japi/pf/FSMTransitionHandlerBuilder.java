/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.japi.pf;

import scala.PartialFunction;
import scala.runtime.BoxedUnit;
import scala.Tuple2;

/**
 * Builder used to create a partial function for {@link akka.actor.FSM#onTransition}.
 *
 * @param <S> the state type
 */
public class FSMTransitionHandlerBuilder<S> {

  private UnitPFBuilder<Tuple2<S, S>> builder =
    new UnitPFBuilder<Tuple2<S, S>>();

  /**
   * Add a case statement that matches on a from state and a to state.
   *
   * @param fromState  the from state to match on
   * @param toState  the to state to match on
   * @param apply  an action to apply when the states match
   * @return the builder with the case statement added
   */
  public FSMTransitionHandlerBuilder<S> state(final S fromState,
                                              final S toState,
                                              final FI.UnitApplyVoid apply) {
    builder.match(Tuple2.class,
      new FI.TypedPredicate<Tuple2>() {
        @Override
        public boolean defined(Tuple2 t) {
          return fromState.equals(t._1()) && toState.equals(t._2());
        }
      },
      new FI.UnitApply<Tuple2>() {
        @Override
        public void apply(Tuple2 t) {
          apply.apply();
        }
      }
    );
    return this;
  }

  /**
   * Build a {@link scala.PartialFunction} from this builder.
   * After this call the builder will be reset.
   *
   * @return  a PartialFunction for this builder.
   */
  public PartialFunction<Tuple2<S, S>, BoxedUnit> build() {
    return builder.build();
  }
}
