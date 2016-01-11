.. _clientSideHTTPS-java:

Client-Side HTTPS Support
=========================

Akka HTTP supports TLS encryption on the client-side as well as on the :ref:`server-side <serverSideHTTPS-java>`.

.. warning:

   Akka HTTP 1.0 does not completely validate certificates when using HTTPS. Please do not treat HTTPS connections
   made with this version as secure. Requests are vulnerable to a Man-In-The-Middle attack via certificate substitution.
   
The central vehicle for configuring encryption is the ``HttpsContext``, which can be created using
the static method ``HttpsContext.create`` which is defined like this:

.. includecode:: /../../akka-http-core/src/main/java/akka/http/javadsl/HttpsContext.java
   :include: http-context-creation

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

Hostname verification
---------------------

Hostname verification proves that the Akka HTTP client is actually communicating with the server it intended to
communicate with. Without this check a man-in-the-middle attack is possible. In the attack scenario, an alternative
certificate would be presented which was issued for another host name. Checking the host name in the certificate
against the host name the connection was opened against is therefore vital.

The default ``HttpsContext`` enables hostname verification. Akka HTTP relies on the `Typesafe SSL-Config`_ library
to implement this and security options for SSL/TLS. Hostname verification is provided by the JDK
and used by Akka HTTP since Java 7, and on Java 6 the verification is implemented by ssl-config manually.

.. note::
  We highly recommend updating your Java runtime to the latest available release,
  preferably JDK 8, as it includes this and many more security features related to TLS.

.. _Typesafe SSL-Config: https://github.com/typesafehub/ssl-config
.. _akka.http.javadsl.Http: @github@/akka-http-core/src/main/scala/akka/http/javadsl/Http.scala
