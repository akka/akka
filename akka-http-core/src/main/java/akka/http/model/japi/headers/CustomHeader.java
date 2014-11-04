/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

public abstract class CustomHeader extends akka.http.model.HttpHeader {
    public abstract String name();
    public abstract String value();

    protected abstract boolean suppressRendering();
}
