/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.amqp.test;

import akka.actor.*;
import com.typesafe.config.ConfigFactory;

@SuppressWarnings({"unchecked"})
public class ExampleSessionJava {

    public static void main(String... args) {
        ActorSystem system = ActorSystem.create("ExampleSessionJava", ConfigFactory.load().getConfig("example"));

        system.actorOf(new Props(MyActor.class));
    }
}
