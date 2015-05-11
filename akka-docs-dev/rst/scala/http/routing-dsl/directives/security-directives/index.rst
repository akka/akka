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
authentication will it be possible to, e.g., "allow access to the statistics resource for _admins_, but not for regular
*members*".

Authentication and authorization may happen at the same time, e.g. when everyone who can properly be authenticated is
also allowed access (which is often a very simple and somewhat implicit authorization logic). In other cases the
system might have one mechanism for authentication (e.g. establishing user identity via an LDAP lookup) and another one
for authorization (e.g. a database lookup for retrieving user access rights).


// TODO: Explain the role of HTTP in this picture (as in, the methods listed here map to HTTP, but there is no general
purpose AAA solution provided here)