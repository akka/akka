/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

public interface LanguageRange {
    public abstract String primaryTag();
    public abstract float qValue();

    public abstract Iterable<String> getSubTags();
    public abstract boolean matches(Language language);

    public abstract LanguageRange withQValue(float qValue);

    public static final LanguageRange ALL = akka.http.model.headers.LanguageRange.$times$.MODULE$;
}
