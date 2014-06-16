/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi;

import akka.http.model.StatusCodes$;
import akka.japi.Option;

/**
 * Contains the set of predefined status-codes along with static methods to access and create custom
 * status-codes.
 */
public final class StatusCodes {
    private StatusCodes() {}

    public static final StatusCode CONTINUE = akka.http.model.StatusCodes.Continue();
    public static final StatusCode SWITCHING_PROTOCOLS = akka.http.model.StatusCodes.SwitchingProtocols();
    public static final StatusCode PROCESSING = akka.http.model.StatusCodes.Processing();

    public static final StatusCode OK = akka.http.model.StatusCodes.OK();
    public static final StatusCode CREATED = akka.http.model.StatusCodes.Created();
    public static final StatusCode ACCEPTED = akka.http.model.StatusCodes.Accepted();
    public static final StatusCode NON_AUTHORITATIVE_INFORMATION = akka.http.model.StatusCodes.NonAuthoritativeInformation();
    public static final StatusCode NO_CONTENT = akka.http.model.StatusCodes.NoContent();
    public static final StatusCode RESET_CONTENT = akka.http.model.StatusCodes.ResetContent();
    public static final StatusCode PARTIAL_CONTENT = akka.http.model.StatusCodes.PartialContent();
    public static final StatusCode MULTI_STATUS = akka.http.model.StatusCodes.MultiStatus();
    public static final StatusCode ALREADY_REPORTED = akka.http.model.StatusCodes.AlreadyReported();
    public static final StatusCode IMUSED = akka.http.model.StatusCodes.IMUsed();

    public static final StatusCode MULTIPLE_CHOICES = akka.http.model.StatusCodes.MultipleChoices();
    public static final StatusCode MOVED_PERMANENTLY = akka.http.model.StatusCodes.MovedPermanently();
    public static final StatusCode FOUND = akka.http.model.StatusCodes.Found();
    public static final StatusCode SEE_OTHER = akka.http.model.StatusCodes.SeeOther();
    public static final StatusCode NOT_MODIFIED = akka.http.model.StatusCodes.NotModified();
    public static final StatusCode USE_PROXY = akka.http.model.StatusCodes.UseProxy();
    public static final StatusCode TEMPORARY_REDIRECT = akka.http.model.StatusCodes.TemporaryRedirect();
    public static final StatusCode PERMANENT_REDIRECT = akka.http.model.StatusCodes.PermanentRedirect();

    public static final StatusCode BAD_REQUEST = akka.http.model.StatusCodes.BadRequest();
    public static final StatusCode UNAUTHORIZED = akka.http.model.StatusCodes.Unauthorized();
    public static final StatusCode PAYMENT_REQUIRED = akka.http.model.StatusCodes.PaymentRequired();
    public static final StatusCode FORBIDDEN = akka.http.model.StatusCodes.Forbidden();
    public static final StatusCode NOT_FOUND = akka.http.model.StatusCodes.NotFound();
    public static final StatusCode METHOD_NOT_ALLOWED = akka.http.model.StatusCodes.MethodNotAllowed();
    public static final StatusCode NOT_ACCEPTABLE = akka.http.model.StatusCodes.NotAcceptable();
    public static final StatusCode PROXY_AUTHENTICATION_REQUIRED = akka.http.model.StatusCodes.ProxyAuthenticationRequired();
    public static final StatusCode REQUEST_TIMEOUT = akka.http.model.StatusCodes.RequestTimeout();
    public static final StatusCode CONFLICT = akka.http.model.StatusCodes.Conflict();
    public static final StatusCode GONE = akka.http.model.StatusCodes.Gone();
    public static final StatusCode LENGTH_REQUIRED = akka.http.model.StatusCodes.LengthRequired();
    public static final StatusCode PRECONDITION_FAILED = akka.http.model.StatusCodes.PreconditionFailed();
    public static final StatusCode REQUEST_ENTITY_TOO_LARGE = akka.http.model.StatusCodes.RequestEntityTooLarge();
    public static final StatusCode REQUEST_URI_TOO_LONG = akka.http.model.StatusCodes.RequestUriTooLong();
    public static final StatusCode UNSUPPORTED_MEDIA_TYPE = akka.http.model.StatusCodes.UnsupportedMediaType();
    public static final StatusCode REQUESTED_RANGE_NOT_SATISFIABLE = akka.http.model.StatusCodes.RequestedRangeNotSatisfiable();
    public static final StatusCode EXPECTATION_FAILED = akka.http.model.StatusCodes.ExpectationFailed();
    public static final StatusCode ENHANCE_YOUR_CALM = akka.http.model.StatusCodes.EnhanceYourCalm();
    public static final StatusCode UNPROCESSABLE_ENTITY = akka.http.model.StatusCodes.UnprocessableEntity();
    public static final StatusCode LOCKED = akka.http.model.StatusCodes.Locked();
    public static final StatusCode FAILED_DEPENDENCY = akka.http.model.StatusCodes.FailedDependency();
    public static final StatusCode UNORDERED_COLLECTION = akka.http.model.StatusCodes.UnorderedCollection();
    public static final StatusCode UPGRADE_REQUIRED = akka.http.model.StatusCodes.UpgradeRequired();
    public static final StatusCode PRECONDITION_REQUIRED = akka.http.model.StatusCodes.PreconditionRequired();
    public static final StatusCode TOO_MANY_REQUESTS = akka.http.model.StatusCodes.TooManyRequests();
    public static final StatusCode REQUEST_HEADER_FIELDS_TOO_LARGE = akka.http.model.StatusCodes.RequestHeaderFieldsTooLarge();
    public static final StatusCode RETRY_WITH = akka.http.model.StatusCodes.RetryWith();
    public static final StatusCode BLOCKED_BY_PARENTAL_CONTROLS = akka.http.model.StatusCodes.BlockedByParentalControls();
    public static final StatusCode UNAVAILABLE_FOR_LEGAL_REASONS = akka.http.model.StatusCodes.UnavailableForLegalReasons();

    public static final StatusCode INTERNAL_SERVER_ERROR = akka.http.model.StatusCodes.InternalServerError();
    public static final StatusCode NOT_IMPLEMENTED = akka.http.model.StatusCodes.NotImplemented();
    public static final StatusCode BAD_GATEWAY = akka.http.model.StatusCodes.BadGateway();
    public static final StatusCode SERVICE_UNAVAILABLE = akka.http.model.StatusCodes.ServiceUnavailable();
    public static final StatusCode GATEWAY_TIMEOUT = akka.http.model.StatusCodes.GatewayTimeout();
    public static final StatusCode HTTPVERSION_NOT_SUPPORTED = akka.http.model.StatusCodes.HTTPVersionNotSupported();
    public static final StatusCode VARIANT_ALSO_NEGOTIATES = akka.http.model.StatusCodes.VariantAlsoNegotiates();
    public static final StatusCode INSUFFICIENT_STORAGE = akka.http.model.StatusCodes.InsufficientStorage();
    public static final StatusCode LOOP_DETECTED = akka.http.model.StatusCodes.LoopDetected();
    public static final StatusCode BANDWIDTH_LIMIT_EXCEEDED = akka.http.model.StatusCodes.BandwidthLimitExceeded();
    public static final StatusCode NOT_EXTENDED = akka.http.model.StatusCodes.NotExtended();
    public static final StatusCode NETWORK_AUTHENTICATION_REQUIRED = akka.http.model.StatusCodes.NetworkAuthenticationRequired();
    public static final StatusCode NETWORK_READ_TIMEOUT = akka.http.model.StatusCodes.NetworkReadTimeout();
    public static final StatusCode NETWORK_CONNECT_TIMEOUT = akka.http.model.StatusCodes.NetworkConnectTimeout();

    /**
     * Registers a custom status code.
     */
    public static StatusCode registerCustom(int intValue, String reason, String defaultMessage, boolean isSuccess, boolean allowsEntity) {
        return akka.http.model.StatusCodes.registerCustom(intValue, reason, defaultMessage, isSuccess, allowsEntity);
    }

    /**
     * Registers a custom status code.
     */
    public static StatusCode registerCustom(int intValue, String reason, String defaultMessage) {
        return akka.http.model.StatusCodes.registerCustom(intValue, reason, defaultMessage);
    }

    /**
     * Looks up a status-code by numeric code. Throws an exception if no such status-code is found.
     */
    public static StatusCode get(int intValue) {
        return akka.http.model.StatusCode.int2StatusCode(intValue);
    }

    /**
     * Looks up a status-code by numeric code and returns Some(code). Returns None otherwise.
     */
    public static Option<StatusCode> lookup(int intValue) {
        return Util.lookupInRegistry(StatusCodes$.MODULE$, intValue);
    }
}
