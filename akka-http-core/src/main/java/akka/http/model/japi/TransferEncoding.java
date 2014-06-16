/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi;

import java.util.Map;

public abstract class TransferEncoding {
    public abstract String name();

    public abstract Map<String, String> getParams();

    public static TransferEncoding createExtension(String name) {
        return new akka.http.model.TransferEncodings.Extension(name, Util.emptyMap);
    }
    public static TransferEncoding createExtension(String name, Map<String, String> params) {
        return new akka.http.model.TransferEncodings.Extension(name, Util.convertMapToScala(params));
    }
}
