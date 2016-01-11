/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi;

import akka.http.model.Uri;

import java.net.InetAddress;
import java.nio.charset.Charset;

/**
 * Represents a host in a URI or a Host header. The host can either be empty or be represented
 * by an IPv4 or IPv6 address or by a host name.
 */
public abstract class Host {
    /**
     * Returns a String representation of the address.
     */
    public abstract String address();
    public abstract boolean isEmpty();
    public abstract boolean isIPv4();
    public abstract boolean isIPv6();
    public abstract boolean isNamedHost();

    /**
     * Returns an Iterable of InetAddresses represented by this Host. If this Host is empty the
     * returned Iterable will be empty. If this is an IP address the Iterable will contain this address.
     * If this Host is represented by a host name, the name will be looked up and return all found
     * addresses for this name.
     */
    public abstract Iterable<InetAddress> getInetAddresses();

    /**
     * The constant representing an empty Host.
     */
    public static final Host EMPTY = akka.http.model.Uri.getHostEmpty();

    /**
     * Parse the given Host string using the default charset and parsing-mode.
     */
    public static Host create(String string) {
        return create(string, Uri.Host$.MODULE$.apply$default$2());
    }

    /**
     * Parse the given Host string using the given charset and the default parsing-mode.
     */
    public static Host create(String string, Charset charset) {
        return Uri.Host$.MODULE$.apply(string, charset, Uri.Host$.MODULE$.apply$default$3());
    }

    /**
     * Parse the given Host string using the given charset and parsing-mode.
     */
    public static Host create(String string, Charset charset, Uri.ParsingMode parsingMode) {
        return Uri.Host$.MODULE$.apply(string, charset, parsingMode);
    }
}
