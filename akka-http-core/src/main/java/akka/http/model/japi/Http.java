/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi;

import akka.actor.ActorSystem;
import akka.http.HttpExt;

public final class Http {
    private Http(){}

    /** Returns the Http extension for an ActorSystem */
    public static HttpExt get(ActorSystem system) {
        return (HttpExt) akka.http.Http.get(system);
    }
    /** Create a Bind message to send to the Http Manager */
    public static Object bind(String host, int port) {
        return Accessors$.MODULE$.Bind(host, port);
    }
}
