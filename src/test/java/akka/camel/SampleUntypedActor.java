package akka.camel;

import akka.actor.UntypedActor;

/**
 * @author Martin Krasser
 */
public class SampleUntypedActor extends UntypedActor {
    public void onReceive(Object message) {
        logger().debug("Yay! I haz a message!");
   }
}
