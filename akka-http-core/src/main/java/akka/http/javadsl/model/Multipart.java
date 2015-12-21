/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.model;

import java.util.Map;
import scala.concurrent.Future;
import akka.http.javadsl.model.headers.ContentDisposition;
import akka.http.javadsl.model.headers.ContentDispositionType;
import akka.http.javadsl.model.headers.RangeUnit;
import akka.japi.Option;
import akka.stream.Materializer;
import akka.stream.javadsl.Source;

/**
 * The model of multipart content for media-types `multipart/\*` (general multipart content),
 * `multipart/form-data` and `multipart/byteranges`.
 *
 * The basic modelling classes for these media-types ([[Multipart.General]], [[Multipart.FormData]] and
 * [[Multipart.ByteRanges]], respectively) are stream-based but each have a strict counterpart
 * (namely [[Multipart.General.Strict]], [[Multipart.FormData.Strict]] and [[Multipart.ByteRanges.Strict]]).
 */
public interface Multipart {

    MediaType.Multipart getMediaType();

    Source<? extends Multipart.BodyPart, Object> getParts();

    /**
     * Converts this content into its strict counterpart.
     * The given `timeout` denotes the max time that an individual part must be read in.
     * The Future is failed with an TimeoutException if one part isn't read completely after the given timeout.
     */
    Future<? extends Multipart.Strict> toStrict(long timeoutMillis, Materializer materializer);

    /**
     * Creates an entity from this multipart object.
     */
    RequestEntity toEntity(HttpCharset charset, String boundary);

    interface Strict extends Multipart {
        Source<? extends Multipart.BodyPart.Strict, Object> getParts();

        Iterable<? extends Multipart.BodyPart.Strict> getStrictParts();

        HttpEntity.Strict toEntity(HttpCharset charset, String boundary);
    }

    interface BodyPart {
        BodyPartEntity getEntity();

        Iterable<HttpHeader> getHeaders();

        Option<ContentDisposition> getContentDispositionHeader();

        Map<String, String> getDispositionParams();

        Option<ContentDispositionType> getDispositionType();

        Future<? extends Multipart.BodyPart.Strict> toStrict(long timeoutMillis, Materializer materializer);

        interface Strict extends Multipart.BodyPart {
            HttpEntity.Strict getEntity();
        }
    }

    /**
     * Basic model for multipart content as defined by http://tools.ietf.org/html/rfc2046.
     */
    interface General extends Multipart {
        Source<? extends Multipart.General.BodyPart, Object> getGeneralParts();

        Future<Multipart.General.Strict> toStrict(long timeoutMillis, Materializer materializer);

        interface Strict extends Multipart.General, Multipart.Strict {
            Source<Multipart.General.BodyPart.Strict, Object> getParts();
            
            Iterable<? extends Multipart.General.BodyPart.Strict> getStrictParts();
        }

        interface BodyPart extends Multipart.BodyPart {
            Future<Multipart.General.BodyPart.Strict> toStrict(long timeoutMillis, Materializer materializer);

            interface Strict extends Multipart.General.BodyPart, Multipart.BodyPart.Strict {
            }
        }
    }

    /**
     * Model for `multipart/form-data` content as defined in http://tools.ietf.org/html/rfc2388.
     * All parts must have distinct names. (This is not verified!)
     */
    interface FormData extends Multipart {
        Source<? extends Multipart.FormData.BodyPart, Object> getFormDataParts();

        Future<Multipart.FormData.Strict> toStrict(long timeoutMillis, Materializer materializer);

        interface Strict extends Multipart.FormData, Multipart.Strict {
            Source<Multipart.FormData.BodyPart.Strict, Object> getParts();

            Iterable<? extends Multipart.FormData.BodyPart.Strict> getStrictParts();
        }

        interface BodyPart extends Multipart.BodyPart {
            String getName();
            Map<String, String> getAdditionalDispositionParams();
            Iterable<HttpHeader> getAdditionalHeaders();
            Option<String> getFilename();

            Future<Multipart.FormData.BodyPart.Strict> toStrict(long timeoutMillis, Materializer materializer);

            interface Strict extends Multipart.FormData.BodyPart, Multipart.BodyPart.Strict {
            }
        }
    }

    /**
     * Model for `multipart/byteranges` content as defined by
     * https://tools.ietf.org/html/rfc7233#section-5.4.1 and https://tools.ietf.org/html/rfc7233#appendix-A
     */
    interface ByteRanges extends Multipart {
        Source<? extends Multipart.ByteRanges.BodyPart, Object> getByteRangeParts();

        Future<Multipart.ByteRanges.Strict> toStrict(long timeoutMillis, Materializer materializer);

        interface Strict extends Multipart.ByteRanges, Multipart.Strict {
            Source<Multipart.ByteRanges.BodyPart.Strict, Object> getParts();

            Iterable<? extends Multipart.ByteRanges.BodyPart.Strict> getStrictParts();
        }

        interface BodyPart extends Multipart.BodyPart {
            ContentRange getContentRange();
            RangeUnit getRangeUnit();
            Iterable<HttpHeader> getAdditionalHeaders();
            akka.http.javadsl.model.headers.ContentRange getContentRangeHeader();

            Future<Multipart.ByteRanges.BodyPart.Strict> toStrict(long timeoutMillis, Materializer materializer);

            interface Strict extends Multipart.ByteRanges.BodyPart, Multipart.BodyPart.Strict {
            }
        }
    }
}
