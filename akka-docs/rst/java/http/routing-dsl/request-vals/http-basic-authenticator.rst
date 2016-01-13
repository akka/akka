.. _http-basic-authenticator-java:

Request Values: Http Basic Auth
===============================

An abstract class to implement HTTP basic authentication

Description
-----------
Http basic auth allows for protection of one or more routes with a username and password.

To use it you subclass ``HttpBasicAuthenticator`` and provide your authentication logic.
There are two factory methods to create the authentication results to return from the authentication logic:
``authenticateAs(T)`` and ``refuseAccess()``. If the authentication is not very quick in memory, for example
calls a database, make sure you do not block the web server thread by executing that in a separate ``Future``
and then ``flatMap`` the result into the authentication result.

When you use the authenticator in your routes you must reference the concrete authenticator twice,
first as a directive wrapping all the routes it should be required for, and then as a request
value to extract the user object for use inside the logic of the handler.

Note that to protect developers from opening up for a timing attack on the password it is not available
directly, instead a constant time string comparison is provided. For more information about timing attacks
on passwords see for example `Timing Attacks Explained`_ .

.. _Timing Attacks Explained: http://emerose.com/timing-attacks-explained


Example
-------

Authenticating or refusing access to a user based on a hardcoded password and using a ``String`` with the
username as internal representation of a user (in a real application it would probably be an instance of
a richer class describing an authenticated user).


.. includecode:: ../../../code/docs/http/javadsl/server/HttpBasicAuthenticatorExample.java
   :include: basic-authenticator-java
