/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel;

import akka.actor.UntypedActor;

/**
 * @author Martin Krasser
 */
public class SampleUntypedActor extends UntypedActor {
    public void onReceive(Object message) {
        System.out.println("Yay! I haz a message!");
   }
}
