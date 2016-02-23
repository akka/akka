/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

public abstract class RangeUnit {
    public abstract String name();

    public static RangeUnit create(String name) {
        return new akka.http.scaladsl.model.headers.RangeUnits.Other(name);
    }
}
