/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model;

import akka.http.impl.util.Util;
import akka.http.scaladsl.model.StatusCodes$;

import java.util.Optional;

/**
 * Contains the set of predefined status-codes along with static methods to access and create custom
 * status-codes.
 */
public final class StatusCodes {
    private StatusCodes() {}

    public static final StatusCode CONTINUE = akka.http.scaladsl.model.StatusCodes.Continue();
    public static final StatusCode SWITCHING_PROTOCOLS = akka.http.scaladsl.model.StatusCodes.SwitchingProtocols();
    public static final StatusCode PROCESSING = akka.http.scaladsl.model.StatusCodes.Processing();

    public static final StatusCode OK = akka.http.scaladsl.model.StatusCodes.OK();
    public static final StatusCode CREATED = akka.http.scaladsl.model.StatusCodes.Created();
    public static final StatusCode ACCEPTED = akka.http.scaladsl.model.StatusCodes.Accepted();
    public static final StatusCode NON_AUTHORITATIVE_INFORMATION = akka.http.scaladsl.model.StatusCodes.NonAuthoritativeInformation();
    public static final StatusCode NO_CONTENT = akka.http.scaladsl.model.StatusCodes.NoContent();
    public static final StatusCode RESET_CONTENT = akka.http.scaladsl.model.StatusCodes.ResetContent();
    public static final StatusCode PARTIAL_CONTENT = akka.http.scaladsl.model.StatusCodes.PartialContent();
    public static final StatusCode MULTI_STATUS = akka.http.scaladsl.model.StatusCodes.MultiStatus();
    public static final StatusCode ALREADY_REPORTED = akka.http.scaladsl.model.StatusCodes.AlreadyReported();
    public static final StatusCode IMUSED = akka.http.scaladsl.model.StatusCodes.IMUsed();

    public static final StatusCode MULTIPLE_CHOICES = akka.http.scaladsl.model.StatusCodes.MultipleChoices();
    public static final StatusCode MOVED_PERMANENTLY = akka.http.scaladsl.model.StatusCodes.MovedPermanently();
    public static final StatusCode FOUND = akka.http.scaladsl.model.StatusCodes.Found();
    public static final StatusCode SEE_OTHER = akka.http.scaladsl.model.StatusCodes.SeeOther();
    public static final StatusCode NOT_MODIFIED = akka.http.scaladsl.model.StatusCodes.NotModified();
    public static final StatusCode USE_PROXY = akka.http.scaladsl.model.StatusCodes.UseProxy();
    public static final StatusCode TEMPORARY_REDIRECT = akka.http.scaladsl.model.StatusCodes.TemporaryRedirect();
    public static final StatusCode PERMANENT_REDIRECT = akka.http.scaladsl.model.StatusCodes.PermanentRedirect();

    public static final StatusCode BAD_REQUEST = akka.http.scaladsl.model.StatusCodes.BadRequest();
    public static final StatusCode UNAUTHORIZED = akka.http.scaladsl.model.StatusCodes.Unauthorized();
    public static final StatusCode PAYMENT_REQUIRED = akka.http.scaladsl.model.StatusCodes.PaymentRequired();
    public static final StatusCode FORBIDDEN = akka.http.scaladsl.model.StatusCodes.Forbidden();
    public static final StatusCode NOT_FOUND = akka.http.scaladsl.model.StatusCodes.NotFound();
    public static final StatusCode METHOD_NOT_ALLOWED = akka.http.scaladsl.model.StatusCodes.MethodNotAllowed();
    public static final StatusCode NOT_ACCEPTABLE = akka.http.scaladsl.model.StatusCodes.NotAcceptable();
    public static final StatusCode PROXY_AUTHENTICATION_REQUIRED = akka.http.scaladsl.model.StatusCodes.ProxyAuthenticationRequired();
    public static final StatusCode REQUEST_TIMEOUT = akka.http.scaladsl.model.StatusCodes.RequestTimeout();
    public static final StatusCode CONFLICT = akka.http.scaladsl.model.StatusCodes.Conflict();
    public static final StatusCode GONE = akka.http.scaladsl.model.StatusCodes.Gone();
    public static final StatusCode LENGTH_REQUIRED = akka.http.scaladsl.model.StatusCodes.LengthRequired();
    public static final StatusCode PRECONDITION_FAILED = akka.http.scaladsl.model.StatusCodes.PreconditionFailed();
    public static final StatusCode REQUEST_ENTITY_TOO_LARGE = akka.http.scaladsl.model.StatusCodes.RequestEntityTooLarge();
    public static final StatusCode REQUEST_URI_TOO_LONG = akka.http.scaladsl.model.StatusCodes.RequestUriTooLong();
    public static final StatusCode UNSUPPORTED_MEDIA_TYPE = akka.http.scaladsl.model.StatusCodes.UnsupportedMediaType();
    public static final StatusCode REQUESTED_RANGE_NOT_SATISFIABLE = akka.http.scaladsl.model.StatusCodes.RequestedRangeNotSatisfiable();
    public static final StatusCode EXPECTATION_FAILED = akka.http.scaladsl.model.StatusCodes.ExpectationFailed();
    public static final StatusCode ENHANCE_YOUR_CALM = akka.http.scaladsl.model.StatusCodes.EnhanceYourCalm();
    public static final StatusCode UNPROCESSABLE_ENTITY = akka.http.scaladsl.model.StatusCodes.UnprocessableEntity();
    public static final StatusCode LOCKED = akka.http.scaladsl.model.StatusCodes.Locked();
    public static final StatusCode FAILED_DEPENDENCY = akka.http.scaladsl.model.StatusCodes.FailedDependency();
    public static final StatusCode UNORDERED_COLLECTION = akka.http.scaladsl.model.StatusCodes.UnorderedCollection();
    public static final StatusCode UPGRADE_REQUIRED = akka.http.scaladsl.model.StatusCodes.UpgradeRequired();
    public static final StatusCode PRECONDITION_REQUIRED = akka.http.scaladsl.model.StatusCodes.PreconditionRequired();
    public static final StatusCode TOO_MANY_REQUESTS = akka.http.scaladsl.model.StatusCodes.TooManyRequests();
    public static final StatusCode REQUEST_HEADER_FIELDS_TOO_LARGE = akka.http.scaladsl.model.StatusCodes.RequestHeaderFieldsTooLarge();
    public static final StatusCode RETRY_WITH = akka.http.scaladsl.model.StatusCodes.RetryWith();
    public static final StatusCode BLOCKED_BY_PARENTAL_CONTROLS = akka.http.scaladsl.model.StatusCodes.BlockedByParentalControls();
    public static final StatusCode UNAVAILABLE_FOR_LEGAL_REASONS = akka.http.scaladsl.model.StatusCodes.UnavailableForLegalReasons();

    public static final StatusCode INTERNAL_SERVER_ERROR = akka.http.scaladsl.model.StatusCodes.InternalServerError();
    public static final StatusCode NOT_IMPLEMENTED = akka.http.scaladsl.model.StatusCodes.NotImplemented();
    public static final StatusCode BAD_GATEWAY = akka.http.scaladsl.model.StatusCodes.BadGateway();
    public static final StatusCode SERVICE_UNAVAILABLE = akka.http.scaladsl.model.StatusCodes.ServiceUnavailable();
    public static final StatusCode GATEWAY_TIMEOUT = akka.http.scaladsl.model.StatusCodes.GatewayTimeout();
    public static final StatusCode HTTPVERSION_NOT_SUPPORTED = akka.http.scaladsl.model.StatusCodes.HTTPVersionNotSupported();
    public static final StatusCode VARIANT_ALSO_NEGOTIATES = akka.http.scaladsl.model.StatusCodes.VariantAlsoNegotiates();
    public static final StatusCode INSUFFICIENT_STORAGE = akka.http.scaladsl.model.StatusCodes.InsufficientStorage();
    public static final StatusCode LOOP_DETECTED = akka.http.scaladsl.model.StatusCodes.LoopDetected();
    public static final StatusCode BANDWIDTH_LIMIT_EXCEEDED = akka.http.scaladsl.model.StatusCodes.BandwidthLimitExceeded();
    public static final StatusCode NOT_EXTENDED = akka.http.scaladsl.model.StatusCodes.NotExtended();
    public static final StatusCode NETWORK_AUTHENTICATION_REQUIRED = akka.http.scaladsl.model.StatusCodes.NetworkAuthenticationRequired();
    public static final StatusCode NETWORK_READ_TIMEOUT = akka.http.scaladsl.model.StatusCodes.NetworkReadTimeout();
    public static final StatusCode NETWORK_CONNECT_TIMEOUT = akka.http.scaladsl.model.StatusCodes.NetworkConnectTimeout();

    /**
     * Create a custom status code.
     */
    public static StatusCode custom(int intValue, String reason, String defaultMessage, boolean isSuccess, boolean allowsEntity) {
        return akka.http.scaladsl.model.StatusCodes.custom(intValue, reason, defaultMessage, isSuccess, allowsEntity);
    }

    /**
     * Create a custom status code.
     */
    public static StatusCode custom(int intValue, String reason, String defaultMessage) {
        return akka.http.scaladsl.model.StatusCodes.custom(intValue, reason, defaultMessage);
    }

    /**
     * Looks up a status-code by numeric code. Throws an exception if no such status-code is found.
     */
    public static StatusCode get(int intValue) {
        return akka.http.scaladsl.model.StatusCode.int2StatusCode(intValue);
    }

    /**
     * Looks up a status-code by numeric code and returns Some(code). Returns None otherwise.
     */
    public static Optional<StatusCode> lookup(int intValue) {
        return Util.<StatusCode, akka.http.scaladsl.model.StatusCode>lookupInRegistry(StatusCodes$.MODULE$, intValue);
    }
}
