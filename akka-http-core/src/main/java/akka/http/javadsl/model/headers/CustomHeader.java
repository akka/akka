/*
 * Copyright (C) 2009-2016 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.model.headers;

public abstract class CustomHeader extends akka.http.scaladsl.model.HttpHeader {
    public abstract String name();
    public abstract String value();
}
