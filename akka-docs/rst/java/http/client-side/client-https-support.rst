.. _clientSideHTTPS-java:

Client-Side HTTPS Support
=========================

Akka HTTP supports TLS encryption on the client-side as well as on the :ref:`server-side <serverSideHTTPS-java>`.

.. warning:

   Akka HTTP 1.0 does not completely validate certificates when using HTTPS. Please do not treat HTTPS connections
   made with this version as secure. Requests are vulnerable to a Man-In-The-Middle attack via certificate substitution.

The central vehicle for configuring encryption is the ``HttpsConnectionContext``, which can be created using
the static method ``ConnectionContext.https`` which is defined like this:

.. includecode:: /../../akka-http-core/src/main/scala/akka/http/javadsl/ConnectionContext.scala
   :include: https-context-creation

In addition to the ``outgoingConnection``, ``newHostConnectionPool`` and ``cachedHostConnectionPool`` methods the
`akka.http.javadsl.Http`_ extension also defines ``outgoingConnectionTls``, ``newHostConnectionPoolTls`` and
``cachedHostConnectionPoolTls``. These methods work identically to their counterparts without the ``-Tls`` suffix,
with the exception that all connections will always be encrypted.

The ``singleRequest`` and ``superPool`` methods determine the encryption state via the scheme of the incoming request,
i.e. requests to an "https" URI will be encrypted, while requests to an "http" URI won't.

The encryption configuration for all HTTPS connections, i.e. the ``HttpsContext`` is determined according to the
following logic:

1. If the optional ``httpsContext`` method parameter is defined it contains the configuration to be used (and thus
   takes precedence over any potentially set default client-side ``HttpsContext``).

2. If the optional ``httpsContext`` method parameter is undefined (which is the default) the default client-side
   ``HttpsContext`` is used, which can be set via the ``setDefaultClientHttpsContext`` on the ``Http`` extension.

3. If no default client-side ``HttpsContext`` has been set via the ``setDefaultClientHttpsContext`` on the ``Http``
   extension the default system configuration is used.

Usually the process is, if the default system TLS configuration is not good enough for your application's needs,
that you configure a custom ``HttpsContext`` instance and set it via ``Http.get(system).setDefaultClientHttpsContext``.
Afterwards you simply use ``outgoingConnectionTls``, ``newHostConnectionPoolTls``, ``cachedHostConnectionPoolTls``,
``superPool`` or ``singleRequest`` without a specific ``httpsContext`` argument, which causes encrypted connections
to rely on the configured default client-side ``HttpsContext``.

If no custom ``HttpsContext`` is defined the default context uses Java's default TLS settings. Customizing the
``HttpsContext`` can make the Https client less secure. Understand what you are doing!

SSL-Config
----------

Akka HTTP heavily relies on, and delegates most configuration of any SSL/TLS related options to
`Lightbend SSL-Config`_, which is a library specialized in providing an secure-by-default SSLContext
and related options.

Please refer to the `Lightbend SSL-Config`_ documentation for detailed documentation of all available settings.

SSL Config settings used by Akka HTTP (as well as Streaming TCP) are located under the `akka.ssl-config` namespace.

.. _Lightbend SSL-Config: http://typesafehub.github.io/ssl-config/

Detailed configuration and workarounds
--------------------------------------

Akka HTTP relies on `Typesafe SSL-Config`_ which is a library maintained by Lightbend that makes configuring
things related to SSL/TLS much simpler than using the raw SSL APIs provided by the JDK. Please refer to its
documentation to learn more about it.

All configuration options available to this library may be set under the ``akka.ssl-context`` configuration for Akka HTTP applications.

.. note::
  When encountering problems connecting to HTTPS hosts we highly encourage to reading up on the excellent ssl-config
  configuration. Especially the quick start sections about `adding certificates to the trust store`_ should prove
  very useful, for example to easily trust a self-signed certificate that applications might use in development mode.

.. warning::
  While it is possible to disable certain checks using the so called "loose" settings in SSL Config, we **strongly recommend**
  to instead attempt to solve these issues by properly configuring TLS–for example by adding trusted keys to the keystore.

  If however certain checks really need to be disabled because of misconfigured (or legacy) servers that your
  application has to speak to, instead of disabling the checks globally (i.e. in ``application.conf``) we suggest
  configuring the loose settings for *specific connections* that are known to need them disabled (and trusted for some other reason).
  The pattern of doing so is documented in the folowing sub-sections.

.. _adding certificates to the trust store: http://typesafehub.github.io/ssl-config/WSQuickStart.html#connecting-to-a-remote-server-over-https

Hostname verification
^^^^^^^^^^^^^^^^^^^^^

Hostname verification proves that the Akka HTTP client is actually communicating with the server it intended to
communicate with. Without this check a man-in-the-middle attack is possible. In the attack scenario, an alternative
certificate would be presented which was issued for another host name. Checking the host name in the certificate
against the host name the connection was opened against is therefore vital.

The default ``HttpsContext`` enables hostname verification. Akka HTTP relies on the `Typesafe SSL-Config`_ library
to implement this and security options for SSL/TLS. Hostname verification is provided by the JDK
and used by Akka HTTP since Java 7, and on Java 6 the verification is implemented by ssl-config manually.

For further recommended reading we would like to highlight the `fixing hostname verification blog post`_ by blog post by Will Sargent.

.. _Typesafe SSL-Config: http://typesafehub.github.io/ssl-config
.. _fixing hostname verification blog post: https://tersesystems.com/2014/03/23/fixing-hostname-verification/
.. _akka.http.javadsl.Http: @github@/akka-http-core/src/main/scala/akka/http/javadsl/Http.scala


Server Name Indication (SNI)
^^^^^^^^^^^^^^^^^^^^^^^^^^^^

SNI is an TLS extension which aims to guard against man-in-the-middle attacks. It does so by having the client send the
name of the virtual domain it is expecting to talk to as part of the TLS handshake.

It is specified as part of `RFC 6066`_.

Disabling TLS security features, at your own risk
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

.. warning::
  It is highly discouraged to disable any of the security features of TLS, however do acknowlage that workarounds may sometimes be needed.

  Before disabling any of the features one should consider if they may be solvable *within* the TLS world,
  for example by `trusting a certificate`_, or `configuring the trusted cipher suites`_.
  There's also a very important section in the ssl-config docs titled `LooseSSL - Please read this before turning anything off!`_.

  If disabling features is indeed desired, we recommend doing so for *specific connections*,
  instead of globally configuring it via ``application.conf``.

The following shows an example of disabling SNI for a given connection:

.. includecode:: ../../code/docs/http/javadsl/HttpsExamplesDocTest.java
   :include: disable-sni-connection

The ``badSslConfig`` is a copy of the default ``AkkaSSLConfig`` with with the slightly changed configuration to disable SNI.
This value can be cached and used for connections which should indeed not use this feature.

.. _RFC 6066: https://tools.ietf.org/html/rfc6066#page-6
.. _LooseSSL - Please read this before turning anything off!: http://typesafehub.github.io/ssl-config/LooseSSL.html#please-read-this-before-turning-anything-off
.. _trusting a certificate: http://typesafehub.github.io/ssl-config/WSQuickStart.html
.. _configuring the trusted cipher suites: http://typesafehub.github.io/ssl-config/CipherSuites.html
