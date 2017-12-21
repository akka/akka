/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

import akka.http.impl.util.Util;

/**
 * @see EntityTagRanges for convenience access to often used values.
 */
public abstract class EntityTagRange {
    public static EntityTagRange create(EntityTag... tags) {
        return akka.http.scaladsl.model.headers.EntityTagRange.apply(Util.<EntityTag, akka.http.scaladsl.model.headers.EntityTag>convertArray(tags));
    }
}
