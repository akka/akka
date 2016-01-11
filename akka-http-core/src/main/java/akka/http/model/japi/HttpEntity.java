/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi;

import akka.http.model.HttpEntity$;
import akka.stream.javadsl.Source;
import akka.util.ByteString;

/**
 * Represents the entity of an Http message. An entity consists of the content-type of the data
 * and the actual data itself. Some subtypes of HttpEntity also define the content-length of the
 * data.
 *
 * An HttpEntity can be of several kinds:
 *
 *  - HttpEntity.Empty: the statically known empty entity
 *  - HttpEntityStrict: an entity containing already evaluated ByteString data
 *  - HttpEntityDefault: the default entity which has a known length and which contains
 *                       a stream of ByteStrings.
 *  - HttpEntityChunked: represents an entity that is delivered using `Transfer-Encoding: chunked`
 *  - HttpEntityCloseDelimited: an entity which doesn't have a fixed length but which is delimited by
 *                              closing the connection.
 *  - HttpEntityIndefiniteLength: an entity which doesn't have a fixed length which can be used to construct BodyParts
 *                                with indefinite length
 *
 *  Marker-interfaces denote which subclasses can be used in which context:
 *  - RequestEntity: an entity type that can be used in an HttpRequest
 *  - ResponseEntity: an entity type that can be used in an HttpResponse
 *  - BodyPartEntity: an entity type that can be used in a BodyPart
 *  - UniversalEntity: an entity type that can be used in every context
 *
 * Use the static constructors in HttpEntities to construct instances.
 */
public interface HttpEntity {
    /**
     * Returns the content-type of this entity
     */
    public abstract ContentType contentType();

    /**
     * The empty entity.
     */
    public static final HttpEntityStrict EMPTY = HttpEntity$.MODULE$.Empty();

    /**
     * Returns if this entity is known to be empty. Open-ended entity types like
     * HttpEntityChunked and HttpCloseDelimited will always return false here.
     */
    public abstract boolean isKnownEmpty();

    /**
     * Returns if this entity is a subtype of HttpEntityChunked.
     */
    public abstract boolean isChunked();

    /**
     * Returns if this entity is a subtype of HttpEntityDefault.
     */
    public abstract boolean isDefault();

    /**
     * Returns if this entity is a subtype of HttpEntityCloseDelimited.
     */
    public abstract boolean isCloseDelimited();

    /**
     * Returns if this entity is a subtype of HttpEntityIndefiniteLength.
     */
    public abstract boolean isIndefiniteLength();

    /**
     * Returns a stream of data bytes this entity consists of.
     */
    public abstract Source<ByteString, ?> getDataBytes();
}
