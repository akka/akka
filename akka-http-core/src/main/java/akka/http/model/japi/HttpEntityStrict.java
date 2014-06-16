/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi;

import akka.util.ByteString;

/**
 * The entity type which consists of a predefined fixed ByteString of data.
 */
public abstract class HttpEntityStrict extends HttpEntityRegular {
    public abstract ByteString data();
}
