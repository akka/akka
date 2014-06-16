/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

public abstract class Host extends akka.http.model.HttpHeader {
    public abstract akka.http.model.japi.Host host();
    public abstract int port();
}
