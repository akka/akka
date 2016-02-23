/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

public interface LanguageRange {
    public abstract String primaryTag();
    public abstract float qValue();
    public abstract boolean matches(Language language);
    public abstract Iterable<String> getSubTags();
    public abstract LanguageRange withQValue(float qValue);

    public static final LanguageRange ALL = akka.http.scaladsl.model.headers.LanguageRange.$times$.MODULE$;
}
