/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

public abstract class EntityTag {
    public abstract String tag();
    public abstract boolean weak();

    public static EntityTag create(String tag, boolean weak) {
        return new akka.http.model.headers.EntityTag(tag, weak);
    }
    public static boolean matchesRange(EntityTag eTag, EntityTagRange range, boolean weak) {
        return akka.http.model.headers.EntityTag.matchesRange(eTag, range, weak);
    }
    public static boolean matches(EntityTag eTag, EntityTag other, boolean weak) {
        return akka.http.model.headers.EntityTag.matches(eTag, other, weak);
    }
}
