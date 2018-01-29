/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

public abstract class RangeUnit {
    public abstract String name();

    public static RangeUnit create(String name) {
        return new akka.http.scaladsl.model.headers.RangeUnits.Other(name);
    }
}
