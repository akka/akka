/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.model.headers;

public abstract class RawHeader extends akka.http.scaladsl.model.HttpHeader {
    public abstract String name();
    public abstract String value();

    public static RawHeader create(String name, String value) {
        return new akka.http.scaladsl.model.headers.RawHeader(name, value);
    }
}
