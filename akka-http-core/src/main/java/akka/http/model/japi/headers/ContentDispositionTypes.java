/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

public final class ContentDispositionTypes {
    private ContentDispositionTypes() {}

    public static final ContentDispositionType INLINE     = akka.http.model.headers.ContentDispositionTypes.inline$.MODULE$;
    public static final ContentDispositionType ATTACHMENT = akka.http.model.headers.ContentDispositionTypes.attachment$.MODULE$;
    public static final ContentDispositionType FORM_DATA  = akka.http.model.headers.ContentDispositionTypes.form$minusdata$.MODULE$;

    public static ContentDispositionType Ext(String name) {
        return new akka.http.model.headers.ContentDispositionTypes.Ext(name);
    }
}
