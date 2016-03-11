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

    /**
     * @deprecated because of troublesome initialisation order (with regards to scaladsl class implementing this class).
     *             In some edge cases this field could end up containing a null value.
     *             Will be removed in Akka 3.x, use {@link LanguageRanges#ALL} instead.
     */
    @Deprecated
    // FIXME: Remove in Akka 3.0
    public static final LanguageRange ALL = LanguageRanges.ALL;
}
