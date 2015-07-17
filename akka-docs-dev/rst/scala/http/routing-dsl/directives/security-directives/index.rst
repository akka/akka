.. _SecurityDirectives:

SecurityDirectives
==================

.. toctree::
   :maxdepth: 1

   authenticateBasic
   authenticateBasicAsync
   authenticateBasicPF
   authenticateBasicPFAsync
   authenticateOrRejectWithChallenge
   authorize
   extractCredentials


Authentication vs. Authorization
--------------------------------

*Authentication* is the process of establishing a known identity for the user, whereby 'identity' is defined in the
context of the application. This may be done with a username/password combination, a cookie, a pre-defined IP or some
other mechanism. After authentication the system believes that it knows who the user is.

*Authorization* is the process of determining, whether a given user is allowed access to a given resource or not. In
most cases, in order to be able to authorize a user (i.e. allow access to some part of the system) the users identity
must already have been established, i.e. he/she must have been authenticated. Without prior authentication the
authorization would have to be very crude, e.g. "allow access for *all* users" or "allow access for *noone*". Only after
authentication will it be possible to, e.g., "allow access to the statistics resource for *admins*, but not for regular
*members*".

Authentication and authorization may happen at the same time, e.g. when everyone who can properly be authenticated is
also allowed access (which is often a very simple and somewhat implicit authorization logic). In other cases the
system might have one mechanism for authentication (e.g. establishing user identity via an LDAP lookup) and another one
for authorization (e.g. a database lookup for retrieving user access rights).


Authentication and Authorization in HTTP
----------------------------------------

HTTP provides a general framework for access control and authentication, via an extensible set of challenge-response
authentication schemes, which can be used by a server to challenge a client request and by a client to provide
authentication information. The general mechanism is defined in `RFC 7235`_.

The "HTTP Authentication Scheme Registry" defines the namespace for the authentication schemes in challenges and
credentials. You can see the currently registered schemes at http://www.iana.org/assignments/http-authschemes.

At this point Akka HTTP only implements the "'Basic' HTTP Authentication Scheme" whose most current specification can be
found here: https://datatracker.ietf.org/doc/draft-ietf-httpauth-basicauth-update/.

.. _RFC 7235: http://tools.ietf.org/html/rfc7235