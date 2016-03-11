/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model;

import akka.http.impl.util.Util;
import akka.http.javadsl.model.headers.EntityTagRanges;

import java.util.Map;

/**
 * @see TransferEncodings for convenience access to often used values.
 */
public abstract class TransferEncoding {
    public abstract String name();

    public abstract Map<String, String> getParams();

    public static TransferEncoding createExtension(String name) {
        return new akka.http.scaladsl.model.TransferEncodings.Extension(name, Util.emptyMap);
    }
    public static TransferEncoding createExtension(String name, Map<String, String> params) {
        return new akka.http.scaladsl.model.TransferEncodings.Extension(name, Util.convertMapToScala(params));
    }
}
