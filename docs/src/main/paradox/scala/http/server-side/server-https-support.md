# Server-Side HTTPS Support

Akka HTTP supports TLS encryption on the server-side as well as on the @ref[client-side](../client-side/client-https-support.md).

The central vehicle for configuring encryption is the @unidoc[HttpsConnectionContext], which can be created using
the static method `ConnectionContext.https` which is defined like this:

Scala
:  @@snip [ConnectionContext.scala]($akka-http$/akka-http-core/src/main/scala/akka/http/scaladsl/ConnectionContext.scala) { #https-context-creation }

Java
:  @@snip [ConnectionContext.scala]($akka-http$/akka-http-core/src/main/scala/akka/http/javadsl/ConnectionContext.scala) { #https-context-creation }

On the server-side the `bind`, and `bindAndHandleXXX` methods of the @scala[@scaladoc[akka.http.scaladsl.Http](akka.http.scaladsl.Http$)]@java[@javadoc[akka.http.javadsl.Http](akka.http.javadsl.Http)] extension define an
optional `httpsContext` parameter, which can receive the HTTPS configuration in the form of an `HttpsContext`
instance.
If defined encryption is enabled on all accepted connections. Otherwise it is disabled (which is the default).

For detailed documentation for client-side HTTPS support refer to @ref[Client-Side HTTPS Support](../client-side/client-https-support.md).

<a id="ssl-config"></a>
## SSL-Config

Akka HTTP heavily relies on, and delegates most configuration of any SSL/TLS related options to
[Lightbend SSL-Config](https://lightbend.github.io/ssl-config/), which is a library specialized in providing an secure-by-default SSLContext
and related options.

Please refer to the [Lightbend SSL-Config](https://lightbend.github.io/ssl-config/) documentation for detailed documentation of all available settings.

SSL Config settings used by Akka HTTP (as well as Streaming TCP) are located under the *akka.ssl-config* namespace.

In order to use SSL-Config in Akka so it logs to the right ActorSystem-wise logger etc., the
`AkkaSSLConfig` extension is provided. Obtaining it is as simple as:

Scala
:  @@snip [HttpsServerExampleSpec.scala]($test$/scala/docs/http/scaladsl/server/HttpsServerExampleSpec.scala) { #akka-ssl-config }

Java
:  @@snip [HttpsServerExampleTest.java]($test$/java/docs/http/javadsl/server/HttpsServerExampleTest.java) { #akka-ssl-config }

While typical usage, for example for configuring http client settings would be applied globally by configuring
ssl-config in `application.conf`, it's possible to obtain the extension and `copy` it while modifying any
configuration that you might need to change and then use that specific `AkkaSSLConfig` instance while establishing
connections be it client or server-side.

## Obtaining SSL/TLS Certificates

In order to run an HTTPS server a certificate has to be provided, which usually is either obtained from a signing
authority or created by yourself for local or staging environment purposes.

Signing authorities often provide instructions on how to create a Java keystore (typically with reference to Tomcat
configuration). If you want to generate your own certificates, the official Oracle documentation on how to generate
keystores using the JDK keytool utility can be found [here](https://docs.oracle.com/javase/8/docs/technotes/tools/unix/keytool.html).

SSL-Config provides a more targeted guide on generating certificates, so we recommend you start with the guide
titled [Generating X.509 Certificates](https://lightbend.github.io/ssl-config/CertificateGeneration.html).

<a id="using-https"></a>
## Using HTTPS

Once you have obtained the server certificate, using it is as simple as preparing an @unidoc[HttpsConnectionContext]
and either setting it as the default one to be used by all servers started by the given @unidoc[Http] extension
or passing it in explicitly when binding the server.

The below example shows how setting up HTTPS works.
First, you create and configure an instance of @unidoc[HttpsConnectionContext] :

Scala
:  @@snip [HttpsServerExampleSpec.scala]($test$/scala/docs/http/scaladsl/server/HttpsServerExampleSpec.scala) { #imports #low-level-default }

Java
:  @@snip [SimpleServerApp.java]($akka-http$/akka-http-tests/src/main/java/akka/http/javadsl/server/examples/simple/SimpleServerApp.java) { #https-http-config }

@scala[Once you configured the HTTPS context, you can set it as default:]
@java[Then pass it to the `akka.http.javadsl.Http` class's `setDefaultServerHttpContext` method, like in the below `main` method.]

Scala
:  @@snip [HttpsServerExampleSpec.scala]($test$/scala/docs/http/scaladsl/server/HttpsServerExampleSpec.scala) { #set-low-level-context-default }

Java
: @@snip [SimpleServerApp.java]($akka-http$/akka-http-tests/src/main/java/akka/http/javadsl/server/examples/simple/SimpleServerApp.java) { #https-http-app }

@@@ div { .group-scala }

It is also possible to pass in the context to specific `bind...` (or client) calls, like displayed below:

@@snip [HttpsServerExampleSpec.scala]($test$/scala/docs/http/scaladsl/server/HttpsServerExampleSpec.scala) { #bind-low-level-context }

@@@

## Running both HTTP and HTTPS

If you want to run HTTP and HTTPS servers in a single application, you can call `bind...` methods twice,
one for HTTPS, and the other for HTTP.

When configuring HTTPS, you can do it up like explained in the above [Using HTTPS](#using-https) section,

Scala
:  @@snip [HttpsServerExampleSpec.scala]($test$/scala/docs/http/scaladsl/server/HttpsServerExampleSpec.scala) { #low-level-default }

Java
:  @@snip [SimpleServerApp.java]($akka-http$/akka-http-tests/src/main/java/akka/http/javadsl/server/examples/simple/SimpleServerApp.java) { #https-http-config }

or via [SSL-Config](#ssl-config) (not explained here though).

Then, call `bind...` methods twice like below.
@scala[The passed `https` context is from the above code snippet.]
@java[`SimpleServerApp.useHttps(system)` is calling the above defined `public static HttpsConnectionContext useHttps(ActorSystem system)` method.]

Scala
:  @@snip [HttpsServerExampleSpec.scala]($test$/scala/docs/http/scaladsl/server/HttpsServerExampleSpec.scala) { #both-https-and-http }

Java
:  @@snip [SimpleServerHttpHttpsApp.java]($akka-http$/akka-http-tests/src/main/java/akka/http/javadsl/server/examples/simple/SimpleServerHttpHttpsApp.java) { #both-https-and-http }

## Mutual authentication

To require clients to authenticate themselves when connecting, pass in @scala[`Some(TLSClientAuth.Need)`]@java[`Optional.of(TLSClientAuth.need)`] as the `clientAuth` parameter of the
@scala[@scaladoc[@unidoc[HttpsConnectionContext]](akka.http.scaladsl.HttpsConnectionContext)]@java[@javadoc[@unidoc[HttpsConnectionContext]](akka.http.javadsl.HttpsConnectionContext)]
and make sure the truststore is populated accordingly. For further (custom) certificate checks you can use the
@scala[@scaladoc[`Tls-Session-Info`](akka.http.scaladsl.model.headers.Tls$minusSession$minusInfo)]@java[@javadoc[`TlsSessionInfo`](akka.http.javadsl.model.headers.TlsSessionInfo)] synthetic header.

At this point dynamic renegotiation of the certificates to be used is not implemented. For details see [issue #18351](https://github.com/akka/akka/issues/18351)
and some preliminary work in [PR #19787](https://github.com/akka/akka/pull/19787).

## Further reading

The topic of properly configuring HTTPS for your web server is an always changing one,
thus we recommend staying up to date with various security breach news and of course
keep your JVM at the latest version possible, as the default settings are often updated by
Oracle in reaction to various security updates and known issues.

We also recommend having a look at the [Play documentation about securing your app](https://www.playframework.com/documentation/2.5.x/ConfiguringHttps#ssl-certificates),
as well as the techniques described in the Play documentation about setting up a [reverse proxy to terminate TLS in
front of your application](https://www.playframework.com/documentation/2.5.x/HTTPServer) instead of terminating TLS inside the JVM, and therefore Akka HTTP, itself.

Other excellent articles on the subject:

 * [Oracle Java SE 8: Creating a Keystore using JSSE](https://docs.oracle.com/javase/8/docs/technotes/guides/security/jsse/JSSERefGuide.html#CreateKeystore)
 * [Java PKI Programmer's Guide](https://docs.oracle.com/javase/8/docs/technotes/guides/security/certpath/CertPathProgGuide.html)
 * [Fixing X.509 Certificates](https://tersesystems.com/2014/03/20/fixing-x509-certificates/)
