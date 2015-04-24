/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.model.headers;

import akka.http.javadsl.model.MediaType;
import akka.http.javadsl.model.Uri;
import akka.http.impl.util.Util;

public abstract class LinkParam {
    public abstract String key();
    public abstract Object value();
}
