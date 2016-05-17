/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

/**
 * The model of an HTTP header. In its most basic form headers are simple name-value pairs. Header names
 * are compared in a case-insensitive way.
 */
public abstract class CustomHeader extends akka.http.scaladsl.model.HttpHeader {
    public abstract String name();
    public abstract String value();
}
