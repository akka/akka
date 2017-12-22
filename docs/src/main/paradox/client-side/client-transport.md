# Pluggable Client Transports / HTTP(S) proxy Support

The client side infrastructure has (unstable) support to plug different transport mechanisms underneath. A client side
transport is represented by an instance of
@scala[@scaladoc[akka.http.scaladsl.ClientTransport](akka.http.scaladsl.ClientTransport)]@java[@javadoc[akka.http.javadsl.ClientTransport](akka.http.javadsl.ClientTransport)]:

Scala
:  @@snip [ClientTransport.scala]($akka-http$/akka-http-core/src/main/scala/akka/http/scaladsl/ClientTransport.scala) { #client-transport-definition }

Java
:  @@snip [ClientTransport.scala]($akka-http$/akka-http-core/src/main/scala/akka/http/javadsl/ClientTransport.scala) { #client-transport-definition }

A transport implementation defines how the client infrastructure should communicate with a given host.

@@@note

In our model, SSL/TLS runs on top of the client transport, even if you could theoretically see it as part of the
transport layer itself.

@@@

## Configuring Client Transports

A @unidoc[ClientTransport] is configured slightly differently for the various layers of the HTTP client.
Right now, configuration is only possible with code (and not through config files). There's currently no
predefined way that would allow you to select different transports per target host (but you can easily define any kind
of strategy by implementing @unidoc[ClientTransport] yourself).

### Connection Pool Usage

The @unidoc[ConnectionPoolSettings] class allows setting a custom transport for any of the pool methods. Use
`ConnectionPoolSettings.withTransport` to configure a transport and pass those settings to one of the
pool methods like
@scala[`Http().singleRequest`, `Http().superPool`, or `Http().cachedHostConnectionPool`]
@java[`Http.get(...).singleRequest`, `Http.get(...).superPool`, or `Http.get(...).cachedHostConnectionPool`].

### Single Connection Usage

You can configure a custom transport for a single HTTP connection by passing it to the `Http().outgoingConnectionUsingTransport`
method.

## Predefined Transports

### TCP

The default transport is `ClientTransport.TCP` which simply opens a TCP connection to the target host.

### HTTP(S) Proxy

A transport that connects to target servers via an HTTP(S) proxy. An HTTP(S) proxy uses the HTTP `CONNECT` method (as
specified in [RFC 7231 Section 4.3.6](https://tools.ietf.org/html/rfc7231#section-4.3.6)) to create tunnels to target
servers. The proxy itself should transparently forward data to the target servers so that end-to-end encryption should
still work (if TLS breaks, then the proxy might be fussing with your data).

This approach is commonly used to securely proxy requests to HTTPS endpoints. In theory it could also be used to proxy
requests targeting HTTP endpoints, but we have not yet found a proxy that in fact allows this.

Instantiate the HTTP(S) proxy transport using `ClientTransport.httpsProxy(proxyAddress)`.

### Use HTTP(S) proxy with @scala[`Http().singleRequest`]@java[`Http.get(...).singleRequest`]

To make use of an HTTP proxy when using the `singleRequest` API you simply need to configure the proxy and pass
the apropriate settings object when calling the single request method.

Scala
:  @@snip [HttpClientExampleSpec.scala]($test$/scala/docs/http/scaladsl/HttpClientExampleSpec.scala) { #https-proxy-example-single-request }

Java
:  @@snip [HttpClientExampleDocTest.java]($test$/java/docs/http/javadsl/HttpClientExampleDocTest.java) { #https-proxy-example-single-request }

### Use HTTP(S) proxy that requires authentication

In order to use a HTTP(S) proxy that requires authentication, you need to provide @unidoc[HttpCredentials] that will be used
when making the CONNECT request to the proxy:


Scala
:  @@snip [HttpClientExampleSpec.scala]($test$/scala/docs/http/scaladsl/HttpClientExampleSpec.scala) { #auth-https-proxy-example-single-request }

Java
:  @@snip [HttpClientExampleDocTest.java]($test$/java/docs/http/javadsl/HttpClientExampleDocTest.java) { #auth-https-proxy-example-single-request }

## Implementing Custom Transports

Implement `ClientTransport.connectTo` to implement a custom client transport.

Here are some ideas for custom (or future predefined) transports:

 * SSH tunnel transport: connects to the target host through an SSH tunnel
 * Per-host configurable transport: allows choosing transports per target host

