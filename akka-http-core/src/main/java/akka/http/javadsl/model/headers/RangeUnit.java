/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.model.headers;

public abstract class RangeUnit {
    public abstract String name();

    public static RangeUnit create(String name) {
        return new akka.http.scaladsl.model.headers.RangeUnits.Other(name);
    }
}
