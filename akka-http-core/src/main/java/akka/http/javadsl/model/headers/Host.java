/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.model.headers;

public abstract class Host extends akka.http.scaladsl.model.HttpHeader {
    public abstract akka.http.javadsl.model.Host host();
    public abstract int port();
}
