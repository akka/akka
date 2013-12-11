/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.japi.pf;

import akka.actor.FSM;
import scala.PartialFunction;
import java.util.List;

/**
 * Builder used to create a partial function for {@link akka.actor.FSM#whenUnhandled}.
 *
 * @param <S> the state type
 * @param <D> the data type
 */
public class FSMStateFunctionBuilder<S, D> {

  private PFBuilder<FSM.Event<D>, FSM.State<S, D>> builder =
    new PFBuilder<FSM.Event<D>, FSM.State<S, D>>();

  /**
   * Add a case statement that matches on an event and data type.
   *
   * @param eventType  the event type to match on
   * @param dataType  the data type to match on
   * @param apply  an action to apply to the event and state data if there is a match
   * @param <P>  the event type to match on
   * @param <Q>  the data type to match on
   * @return the builder with the case statement added
   */
  public <P, Q> FSMStateFunctionBuilder<S, D> event(final Class<P> eventType,
                                                    final Class<Q> dataType,
                                                    final FI.Apply2<P, Q, FSM.State<S, D>> apply) {
    builder.match(FSM.Event.class,
      new FI.TypedPredicate<FSM.Event>() {
        @Override
        public boolean defined(FSM.Event e) {
          return eventType.isInstance(e.event()) && dataType.isInstance(e.stateData());
        }
      },
      new FI.Apply<FSM.Event, FSM.State<S, D>>() {
        public FSM.State<S, D> apply(FSM.Event e) {
          @SuppressWarnings("unchecked")
          P p = (P) e.event();
          @SuppressWarnings("unchecked")
          Q q = (Q) e.stateData();
          return apply.apply(p, q);
        }
      }
    );

    return this;
  }

  /**
   * Add a case statement that matches on the data type and if any of the event types
   * in the list match or any of the event instances in the list compares equal.
   *
   * @param eventMatches  a list of types or instances to match against
   * @param dataType  the data type to match on
   * @param apply  an action to apply to the event and state data if there is a match
   * @param <Q>  the data type to match on
   * @return the builder with the case statement added
   */
  public <Q> FSMStateFunctionBuilder<S, D> event(final List<Object> eventMatches,
                                                 final Class<Q> dataType,
                                                 final FI.Apply<Q, FSM.State<S, D>> apply) {
    builder.match(FSM.Event.class,
      new FI.TypedPredicate<FSM.Event>() {
        @Override
        public boolean defined(FSM.Event e) {
          if (!dataType.isInstance(e.stateData()))
            return false;

          boolean emMatch = false;
          Object event = e.event();
          for (Object em : eventMatches) {
            if (em instanceof Class) {
              Class emc = (Class) em;
              emMatch = emc.isInstance(event);
            } else {
              emMatch = event.equals(em);
            }
            if (emMatch)
              break;
          }
          return emMatch;
        }
      },
      new FI.Apply<FSM.Event, FSM.State<S, D>>() {
        public FSM.State<S, D> apply(FSM.Event e) {
          @SuppressWarnings("unchecked")
          Q q = (Q) e.stateData();
          return apply.apply(q);
        }
      }
    );

    return this;
  }

  /**
   * Add a case statement that matches on any type of event.
   *
   * @param apply  an action to apply to the event and state data
   * @return the builder with the case statement added
   */
  public FSMStateFunctionBuilder<S, D> anyEvent(final FI.Apply2<Object, D, FSM.State<S, D>> apply) {
    builder.match(FSM.Event.class,
      new FI.Apply<FSM.Event, FSM.State<S, D>>() {
        public FSM.State<S, D> apply(FSM.Event e) {
          @SuppressWarnings("unchecked")
          D d = (D) e.stateData();
          return apply.apply(e.event(), d);
        }
      });

    return this;
  }

  /**
   * Build a {@link scala.PartialFunction} from this builder.
   * After this call the builder will be reset.
   *
   * @return  a PartialFunction for this builder.
   */
  public PartialFunction<FSM.Event<D>, FSM.State<S, D>> build() {
    return builder.build();
  }
}
