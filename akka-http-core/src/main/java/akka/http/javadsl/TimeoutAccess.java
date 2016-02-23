/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.japi.Function;
import scala.concurrent.duration.Duration;

/**
 * Enables programmatic access to the server-side request timeout logic.
 */
public interface TimeoutAccess {

  /**
   * Tries to set a new timeout.
   * The timeout period is measured as of the point in time that the end of the request has been received,
   * which may be in the past or in the future!
   * Use `Duration.Inf` to completely disable request timeout checking for this request.
   *
   * Due to the inherent raciness it is not guaranteed that the update will be applied before
   * the previously set timeout has expired!
   */
  void updateTimeout(Duration timeout);

  /**
   * Tries to set a new timeout handler, which produces the timeout response for a
   * given request. Note that the handler must produce the response synchronously and shouldn't block!
   *
   * Due to the inherent raciness it is not guaranteed that the update will be applied before
   * the previously set timeout has expired!
   */
  void updateHandler(Function<HttpRequest, HttpResponse> handler);

  /**
   * Tries to set a new timeout and handler at the same time.
   *
   * Due to the inherent raciness it is not guaranteed that the update will be applied before
   * the previously set timeout has expired!
   */
  void update(Duration timeout, Function<HttpRequest, HttpResponse> handler);
}
