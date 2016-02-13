/**
 * Copyright (C) 2009-2016 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.model.headers;

public interface LanguageRange {
    String primaryTag();
    float qValue();
    boolean matches(Language language);
    Iterable<String> getSubTags();
    LanguageRange withQValue(float qValue);

    LanguageRange ALL = akka.http.scaladsl.model.headers.LanguageRange.$times$.MODULE$;
}
