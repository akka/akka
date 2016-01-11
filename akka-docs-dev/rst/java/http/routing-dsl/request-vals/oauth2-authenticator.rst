.. _oauth2-authenticator-java:

Request Values: OAuth 2 Bearer Token Authentication
===================================================

An abstract class to implement Oauth 2 bearer token authentication

Description
-----------
Allows to protect one of more routes with authentication in the form of a OAuth2 Bearer Token. For more information
about OAuth 2 Bearer Token see `RFC6750`_.

.. _RFC6750: https://tools.ietf.org/html/rfc6750

To use it you subclass ``OAuth2Authenticator`` and implement the ``authenticate`` method
to provide your own logic which verifies the OAuth2 credentials. When verification is done
the request can either be refused by returning the return value of ``refuseAccess()`` or completed
with an object that is application specific by returning the return value of ``authenticateAs(T)``.

If the authentication is not very quick in memory, for example calls a separate authentication server
to verify the token, make sure you do not block the web server thread by executing that in a separate ``Future``
and then ``flatMap`` the result into the authentication result.

.. note:: OAuth2 Bearer Token sends the token as clear text and should ONLY EVER be used over
          SSL/TLS

When you use the OAuth2 authenticator in your routes you must reference the concrete authenticator twice,
first as a directive wrapping all the routes it should be required for, and then as a request
value to extract the user object for use inside the logic of the handler.

Note that to protect developers from opening up for a timing attack on the token it is not available
directly, instead a constant time string comparison is provided. For more information about timing attacks
on passwords see for example `Timing Attacks Explained`_ .

.. _Timing Attacks Explained: http://emerose.com/timing-attacks-explained


Example
-------

Authenticating or refusing access to a user based on a hardcoded token and using a ``String`` with the
identity as internal representation of a user (in a real application it would probably be an instance of
a richer class describing an authenticated user).


.. includecode:: ../../../code/docs/http/javadsl/server/OAuth2AuthenticatorExample.java
   :include: oauth2-authenticator-java
