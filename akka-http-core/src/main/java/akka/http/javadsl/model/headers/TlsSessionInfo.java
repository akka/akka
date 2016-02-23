/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

import javax.net.ssl.SSLSession;

/**
 * Model for the synthetic `Tls-Session-Info` header which carries the SSLSession of the connection
 * the message carrying this header was received with.
 *
 * This header will only be added if it enabled in the configuration by setting
 * <code>akka.http.[client|server].parsing.tls-session-info-header = on</code>.
 */
public abstract class TlsSessionInfo extends CustomHeader {
    /**
     * @return the SSLSession this message was received over.
     */
    public abstract SSLSession getSession();

    public static TlsSessionInfo create(SSLSession session) {
        return akka.http.scaladsl.model.headers.Tls$minusSession$minusInfo$.MODULE$.apply(session);
    }
}
