/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

public final class CacheDirectives {
    private CacheDirectives() {}

    public static CacheDirective MAX_AGE(long deltaSeconds) {
        return new akka.http.model.headers.CacheDirectives.max$minusage(deltaSeconds);
    }
    public static CacheDirective MAX_STALE() {
        return new akka.http.model.headers.CacheDirectives.max$minusstale(akka.japi.Option.none().asScala());
    }
    public static CacheDirective MAX_STALE(long deltaSeconds) {
        return new akka.http.model.headers.CacheDirectives.max$minusstale(akka.japi.Option.some((Object) deltaSeconds).asScala());
    }
    public static CacheDirective MIN_FRESH(long deltaSeconds) {
        return new akka.http.model.headers.CacheDirectives.min$minusfresh(deltaSeconds);
    }

    public static final CacheDirective NO_CACHE        = akka.http.model.headers.CacheDirectives.no$minuscache$.MODULE$;
    public static final CacheDirective NO_STORE        = akka.http.model.headers.CacheDirectives.no$minusstore$.MODULE$;
    public static final CacheDirective NO_TRANSFORM    = akka.http.model.headers.CacheDirectives.no$minustransform$.MODULE$;
    public static final CacheDirective ONLY_IF_CACHED  = akka.http.model.headers.CacheDirectives.only$minusif$minuscached$.MODULE$;
    public static final CacheDirective MUST_REVALIDATE = akka.http.model.headers.CacheDirectives.must$minusrevalidate$.MODULE$;

    public static CacheDirective NO_CACHE(String... fieldNames) {
        return akka.http.model.headers.CacheDirectives.no$minuscache$.MODULE$.apply(fieldNames);
    }
    public static final CacheDirective PUBLIC = akka.http.model.headers.CacheDirectives.public$.MODULE$;
    public static CacheDirective PRIVATE(String... fieldNames) {
        return akka.http.model.headers.CacheDirectives.private$.MODULE$.apply(fieldNames);
    }
    public static final CacheDirective PROXY_REVALIDATE = akka.http.model.headers.CacheDirectives.proxy$minusrevalidate$.MODULE$;
    public static CacheDirective S_MAXAGE(long deltaSeconds) {
        return new akka.http.model.headers.CacheDirectives.s$minusmaxage(deltaSeconds);
    }
}
