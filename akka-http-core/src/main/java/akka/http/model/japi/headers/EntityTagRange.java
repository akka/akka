/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

import akka.http.model.japi.Util;

public abstract class EntityTagRange {
    public static EntityTagRange create(EntityTag... tags) {
        return akka.http.model.headers.EntityTagRange.apply(Util.<EntityTag, akka.http.model.headers.EntityTag>convertArray(tags));
    }
    public static final EntityTagRange ALL = akka.http.model.headers.EntityTagRange.$times$.MODULE$;
}
