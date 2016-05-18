.. _serverSideHTTPS-scala:

Server-Side HTTPS Support
=========================

Akka HTTP supports TLS encryption on the server-side as well as on the :ref:`client-side <clientSideHTTPS>`.

The central vehicle for configuring encryption is the ``HttpsConnectionContext``, which can be created using
the static method ``ConnectionContext.https`` which is defined like this:

.. includecode:: /../../akka-http-core/src/main/scala/akka/http/scaladsl/ConnectionContext.scala
   :include: https-context-creation

On the server-side the ``bind``, and ``bindAndHandleXXX`` methods of the `akka.http.scaladsl.Http`_ extension define an
optional ``httpsContext`` parameter, which can receive the HTTPS configuration in the form of an ``HttpsContext``
instance.
If defined encryption is enabled on all accepted connections. Otherwise it is disabled (which is the default).

For detailed documentation for client-side HTTPS support refer to :ref:`clientSideHTTPS`.


.. _akka.http.scaladsl.Http: https://github.com/akka/akka/blob/master/akka-http-core/src/main/scala/akka/http/scaladsl/Http.scala

SSL-Config
----------

Akka HTTP heavily relies on, and delegates most configuration of any SSL/TLS related options to
`Lightbend SSL-Config`_, which is a library specialized in providing an secure-by-default SSLContext
and related options.

Please refer to the `Lightbend SSL-Config`_ documentation for detailed documentation of all available settings.

SSL Config settings used by Akka HTTP (as well as Streaming TCP) are located under the `akka.ssl-config` namespace.

.. _Lightbend SSL-Config: http://typesafehub.github.io/ssl-config/

In order to use SSL-Config in Akka so it logs to the right ActorSystem-wise logger etc., the
``AkkaSSLConfig`` extension is provided. Obtaining it is as simple as:

.. includecode2:: ../code/docs/http/scaladsl/server/HttpsServerExampleSpec.scala
   :snippet: akka-ssl-config

While typical usage, for example for configuring http client settings would be applied globally by configuring
ssl-config in ``application.conf``, it's possible to obtain the extension and ``copy`` it while modifying any
configuration that you might need to change and then use that specific ``AkkaSSLConfig`` instance while establishing
connections be it client or server-side.

Obtaining SSL/TLS Certificates
------------------------------
In order to run an HTTPS server a certificate has to be provided, which usually is either obtained from a signing
authority or created by yourself for local or staging environment purposes.

Signing authorities often provide instructions on how to create a Java keystore (typically with reference to Tomcat
configuration). If you want to generate your own certificates, the official Oracle documentation on how to generate
keystores using the JDK keytool utility can be found `here <https://docs.oracle.com/javase/8/docs/technotes/tools/unix/keytool.html>`_.

SSL-Config provides a more targeted guide on generating certificates, so we recommend you start with the guide
titled `Generating X.509 Certificates <http://typesafehub.github.io/ssl-config/CertificateGeneration.html>`_.

Using HTTPS
-----------

Once you have obtained the server certificate, using it is as simple as preparing an ``HttpsConnectionContext``
and either setting it as the default one to be used by all servers started by the given ``Http`` extension
or passing it in explicitly when binding the server:


.. includecode2:: ../code/docs/http/scaladsl/server/HttpsServerExampleSpec.scala
   :snippet: imports

.. includecode2:: ../code/docs/http/scaladsl/server/HttpsServerExampleSpec.scala
   :snippet: low-level-default

It is also possible to pass in the context to specific ``bind...`` (or client) calls, like displayed below:

.. includecode2:: ../code/docs/http/scaladsl/server/HttpsServerExampleSpec.scala
   :snippet: bind-low-level-context




Further reading
---------------

The topic of properly configuring HTTPS for your web server is an always changing one,
thus we recommend staying up to date with various security breach news and of course
keep your JVM at the latest version possible, as the default settings are often updated by
Oracle in reaction to various security updates and known issues.

We also recommend having a look at the `Play documentation about securing your app`_,
as well as the techniques described in the Play documentation about setting up a `reverse proxy to terminate TLS in
front of your application`_ instead of terminating TLS inside the JVM, and therefore Akka HTTP, itself.

Other excellent articles on the subject:

- `Oracle Java SE 8: Creating a Keystore using JSSE <https://docs.oracle.com/javase/8/docs/technotes/guides/security/jsse/JSSERefGuide.html#CreateKeystore>`_
- `Java PKI Programmer's Guide <https://docs.oracle.com/javase/8/docs/technotes/guides/security/certpath/CertPathProgGuide.html>`_
- `Fixing X.509 Certificates <https://tersesystems.com/2014/03/20/fixing-x509-certificates/>`_

.. _Play documentation about securing your app: https://www.playframework.com/documentation/2.5.x/ConfiguringHttps#ssl-certificates
.. _reverse proxy to terminate TLS in front of your application: https://www.playframework.com/documentation/2.5.x/HTTPServer