/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

import akka.http.impl.util.Util;
import akka.http.scaladsl.model.headers.Language$;

public abstract class Language {
    public static Language create(String primaryTag, String... subTags) {
        return Language$.MODULE$.apply(primaryTag, Util.<String, String>convertArray(subTags));
    }

    public abstract String primaryTag();
    public abstract Iterable<String> getSubTags();
    public abstract LanguageRange withQValue(float qValue);
}
