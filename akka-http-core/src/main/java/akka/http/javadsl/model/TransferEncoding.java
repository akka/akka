/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model;

import akka.http.impl.util.Util;

import java.util.Map;

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
