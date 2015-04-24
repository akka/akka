/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.model.headers;

import akka.http.impl.util.Util;
import akka.http.scaladsl.model.headers.Language$;

public abstract class Language implements LanguageRange {
    public static Language create(String primaryTag, String... subTags) {
        return Language$.MODULE$.apply(primaryTag, Util.<String, String>convertArray(subTags));
    }
}
