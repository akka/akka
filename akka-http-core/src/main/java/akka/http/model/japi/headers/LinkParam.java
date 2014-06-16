/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

import akka.http.model.japi.MediaType;
import akka.http.model.japi.Uri;
import akka.http.model.japi.Util;

public abstract class LinkParam {
    public abstract String key();
    public abstract Object value();
}
