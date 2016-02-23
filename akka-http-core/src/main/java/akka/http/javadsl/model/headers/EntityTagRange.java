/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

import akka.http.impl.util.Util;

public abstract class EntityTagRange {
    public static EntityTagRange create(EntityTag... tags) {
        return akka.http.scaladsl.model.headers.EntityTagRange.apply(Util.<EntityTag, akka.http.scaladsl.model.headers.EntityTag>convertArray(tags));
    }
    public static final EntityTagRange ALL = akka.http.scaladsl.model.headers.EntityTagRange.$times$.MODULE$;
}
