package akka.camel;

import akka.actor.TypedActor;

/**
 * @author Martin Krasser
 */
public class SampleTypedActorImpl implements SampleTypedActor {

    public String foo(String s) {
        return String.format("foo: %s", s);
    }

}
