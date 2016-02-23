/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

import scala.compat.java8.OptionConverters;

import java.util.Optional;
import java.util.OptionalLong;

public final class CacheDirectives {
    private CacheDirectives() {}

    public static CacheDirective MAX_AGE(long deltaSeconds) {
        return new akka.http.scaladsl.model.headers.CacheDirectives.max$minusage(deltaSeconds);
    }
    public static CacheDirective MAX_STALE() {
        return new akka.http.scaladsl.model.headers.CacheDirectives.max$minusstale(OptionConverters.toScala(Optional.empty()));
    }
    public static CacheDirective MAX_STALE(long deltaSeconds) {
        return new akka.http.scaladsl.model.headers.CacheDirectives.max$minusstale(OptionConverters.toScala(OptionalLong.of(deltaSeconds)));
    }
    public static CacheDirective MIN_FRESH(long deltaSeconds) {
        return new akka.http.scaladsl.model.headers.CacheDirectives.min$minusfresh(deltaSeconds);
    }

    public static final CacheDirective NO_CACHE        = akka.http.scaladsl.model.headers.CacheDirectives.no$minuscache$.MODULE$;
    public static final CacheDirective NO_STORE        = akka.http.scaladsl.model.headers.CacheDirectives.no$minusstore$.MODULE$;
    public static final CacheDirective NO_TRANSFORM    = akka.http.scaladsl.model.headers.CacheDirectives.no$minustransform$.MODULE$;
    public static final CacheDirective ONLY_IF_CACHED  = akka.http.scaladsl.model.headers.CacheDirectives.only$minusif$minuscached$.MODULE$;
    public static final CacheDirective MUST_REVALIDATE = akka.http.scaladsl.model.headers.CacheDirectives.must$minusrevalidate$.MODULE$;

    public static CacheDirective NO_CACHE(String... fieldNames) {
        return akka.http.scaladsl.model.headers.CacheDirectives.no$minuscache$.MODULE$.apply(akka.japi.Util.immutableSeq(fieldNames));
    }
    public static final CacheDirective PUBLIC = akka.http.scaladsl.model.headers.CacheDirectives.getPublic();
    public static CacheDirective PRIVATE(String... fieldNames) {
        return akka.http.scaladsl.model.headers.CacheDirectives.createPrivate(fieldNames);
    }
    public static final CacheDirective PROXY_REVALIDATE = akka.http.scaladsl.model.headers.CacheDirectives.proxy$minusrevalidate$.MODULE$;
    public static CacheDirective S_MAXAGE(long deltaSeconds) {
        return new akka.http.scaladsl.model.headers.CacheDirectives.s$minusmaxage(deltaSeconds);
    }
}
