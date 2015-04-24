/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.model;

import akka.util.ByteString;

/**
 * The entity type which consists of a predefined fixed ByteString of data.
 */
public abstract class HttpEntityStrict implements BodyPartEntity, RequestEntity, ResponseEntity {
    public abstract ByteString data();
}
