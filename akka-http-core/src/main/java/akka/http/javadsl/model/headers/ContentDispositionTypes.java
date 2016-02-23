/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

public final class ContentDispositionTypes {
    private ContentDispositionTypes() {}

    public static final ContentDispositionType INLINE     = akka.http.scaladsl.model.headers.ContentDispositionTypes.inline$.MODULE$;
    public static final ContentDispositionType ATTACHMENT = akka.http.scaladsl.model.headers.ContentDispositionTypes.attachment$.MODULE$;
    public static final ContentDispositionType FORM_DATA  = akka.http.scaladsl.model.headers.ContentDispositionTypes.form$minusdata$.MODULE$;

    public static ContentDispositionType Ext(String name) {
        return new akka.http.scaladsl.model.headers.ContentDispositionTypes.Ext(name);
    }
}
