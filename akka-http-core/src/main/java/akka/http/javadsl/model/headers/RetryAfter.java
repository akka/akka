/**
 * Copyright 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

import java.util.Optional;
import akka.http.impl.util.Util;
import akka.http.javadsl.model.DateTime;
import akka.http.scaladsl.model.headers.Retry$minusAfter;
import akka.http.scaladsl.model.headers.RetryAfterDuration;
import akka.http.scaladsl.model.headers.RetryAfterDateTime;
/**
 *  Model for the `Retry-After` header.
 *  Specification: //https://tools.ietf.org/html/rfc7231#section-7.1.3
 */
public abstract class RetryAfter extends akka.http.scaladsl.model.HttpHeader {

    protected abstract scala.Option<Long> delaySeconds();
	  
    protected abstract scala.Option<akka.http.scaladsl.model.DateTime> dateTime();

    /** number of seconds for the retry attempt, if available */
    public Optional<Long> getDelaySeconds() {
    	return Util.convertOption(delaySeconds());
    }

    /** the date for the retry attempt, if available */
    public Optional<DateTime> getDateTime() {
    	return Util.convertOption(dateTime());
    }

    public static RetryAfter create(Long delaySeconds) {
        return new Retry$minusAfter(new RetryAfterDuration(delaySeconds));
    }

    public static RetryAfter create(DateTime dateTime) {
        return new Retry$minusAfter(new RetryAfterDateTime((akka.http.scaladsl.model.DateTime) dateTime));
    }
}
