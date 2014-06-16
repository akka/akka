/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

import akka.http.model.headers.Language$;
import akka.http.model.japi.Util;

public abstract class Language implements LanguageRange {
    public static Language create(String primaryTag, String... subTags) {
        return Language$.MODULE$.apply(primaryTag, Util.<String, String>convertArray(subTags));
    }
}
