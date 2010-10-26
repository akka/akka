package akka.camel;

import akka.actor.TypedActor;

/**
 * @author Martin Krasser
 */
public class SampleTypedActorImpl extends TypedActor implements SampleTypedActor {

    public String foo(String s) {
        return String.format("foo: %s", s);
    }

}
