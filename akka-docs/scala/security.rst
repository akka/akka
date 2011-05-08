HTTP Security
=============

.. sidebar:: Contents

   .. contents:: :local:

Module stability: **IN PROGRESS**

Akka supports security for access to RESTful Actors through `HTTP Authentication <http://en.wikipedia.org/wiki/HTTP_Authentication>`_. The security is implemented as a jersey ResourceFilter which delegates the actual authentication to an authentication actor.

Akka provides authentication via the following authentication schemes:

* `Basic Authentication <http://en.wikipedia.org/wiki/Basic_access_authentication>`_
* `Digest Authentication <http://en.wikipedia.org/wiki/Digest_access_authentication>`_
* `Kerberos SPNEGO Authentication <http://en.wikipedia.org/wiki/SPNEGO>`_

The authentication is performed by implementations of akka.security.AuthenticationActor.

Akka provides a trait for each authentication scheme:

* BasicAuthenticationActor
* DigestAuthenticationActor
* SpnegoAuthenticationActor


Setup
-----

To secure your RESTful actors you need to perform the following steps:

1. configure the resource filter factory 'akka.security.AkkaSecurityFilterFactory' in the 'akka.conf' like this:

.. code-block:: ruby

  akka {
    ...
    rest {
      filters="akka.security.AkkaSecurityFilterFactory"
    }
   ...
  }

2. Configure an implementation of an authentication actor in 'akka.conf':

.. code-block:: ruby

  akka {
    ...
    rest {
      filters= ...
      authenticator = "akka.security.samples.BasicAuthenticationService"
    }
   ...
  }

3. Start your authentication actor in your 'Boot' class. The security package consists of the following parts:

4. Secure your RESTful actors using class or resource level annotations:

* @DenyAll
* @RolesAllowed(listOfRoles)
* @PermitAll

Security Samples
----------------

The akka-samples-security module contains a small sample application with sample implementations for each authentication scheme.
You can start the sample app using the jetty plugin: mvn jetty:run.

The RESTful actor can then be accessed using your browser of choice under:

* permit access only to users having the “chef” role: `<http://localhost:8080//secureticker/chef>`_
* public access: `<http://localhost:8080//secureticker/public>`_

You can access the secured resource using any user for basic authentication (which is the default authenticator in the sample app).

Digest authentication can be directly enabled in the sample app. Kerberos/SPNEGO authentication is a bit more involved an is described below.


Kerberos/SPNEGO Authentication
------------------------------

Kerberos is a network authentication protocol, (see `<http://www.ietf.org/rfc/rfc1510.txt>`_). It provides strong authentication for client/server applications.
In a kerberos enabled environment a user will need to sign on only once. Subsequent authentication to applications is handled transparently by kerberos.

Most prominently the kerberos protocol is used to authenticate users in a windows network. When deploying web applications to a corporate intranet an important feature will be to support the single sign on (SSO), which comes to make the application kerberos aware.

How does it work (at least for REST actors)?

- When accessing a secured resource the server will check the request for the *Authorization* header as with basic or digest authentication.
- If it is not set, the server will respond with a challenge to "Negotiate". The negotiation is in fact the NEGO part of the `SPNEGO <http://tools.ietf.org/html/rfc4178>`_ specification
- The browser will then try to acquire a so called *service ticket* from a ticket granting service, i.e. the kerberos server
- The browser will send the *service ticket* to the web application encoded in the header value of the *Authorization* header
- The web application must validate the ticket based on a shared secret between the web application and the kerberos server. As a result the web application will know the name of the user

To activate the kerberos/SPNEGO authentication for your REST actor you need to enable the kerberos/SPNEGOauthentication actor in the akka.conf like this:

.. code-block:: ruby

  akka {
    ...
    rest {
      filters= ...
      authenticator = "akka.security.samples.SpnegoAuthenticationService"
    }
   ...
  }

Furthermore you must provide the SpnegoAuthenticator with the following information.

- Service principal name: the name of your web application in the kerberos servers user database. This name is always has the form ``HTTP/{server}@{realm}``
- Path to the keytab file: this is a kind of certificate for your web application to acquire tickets from the kerberos server

.. code-block:: ruby

  akka {
    ...
    rest {
      filters= ...
      authenticator = "akka.security.samples.SpnegoAuthenticationService"
      kerberos {
          servicePrincipal = "HTTP/{server}@{realm}"
          keyTabLocation   = "URL to keytab"
  #        kerberosDebug    = "true"
      }
    }
   ...
  }


How to setup kerberos on localhost for Ubuntu
---------------------------------------------

This is a short step by step description of howto set up a kerberos server on an ubuntu system.

1. Install the Heimdal Kerberos Server and Client

::

  sudo apt-get install heimdal-clients heimdal-clients-x heimdal-kdc krb5-config
  ...

2. Set up your kerberos realm. In this example the realm is of course … EXAMPLE.COM

::

  eckart@dilbert:~$ sudo kadmin -l
  kadmin> init EXAMPLE.COM
  Realm max ticket life [unlimited]:
  Realm max renewable ticket life [unlimited]:
  kadmin> quit

3. Tell your kerberos clients what your realm is and where to find the kerberos server (aka the Key Distribution Centre or KDC)

Edit the kerberos config file: /etc/krb5.conf and configure …
…the default realm:

::

  [libdefaults]
   default_realm = EXAMPLE.COM

… where to find the KDC for your realm

::

  [realms]
          EXAMPLE.COM = {
                 kdc = localhost
          }

…which hostnames or domains map to which realm (a kerberos realm is **not** a DNS domain):

::

  [domain_realm]
          localhost = EXAMPLE.COM

4. Add the principals
The user principal:

::

  eckart@dilbert:~$ sudo kadmin -l
  kadmin> add zaphod
  Max ticket life [1 day]:
  Max renewable life [1 week]:
  Principal expiration time [never]:
  Password expiration time [never]:
  Attributes []:
  zaphod@EXAMPLE.COM's Password:
  Verifying - zaphod@EXAMPLE.COM's Password:
  kadmin> quit

The service principal:

::

  eckart@dilbert:~$ sudo kadmin -l
  kadmin> add HTTP/localhost@EXAMPLE.COM
  Max ticket life [1 day]:
  Max renewable life [1 week]:
  Principal expiration time [never]:
  Password expiration time [never]:
  Attributes []:
  HTTP/localhost@EXAMPLE.COM's Password:
  Verifying - HTTP/localhost@EXAMPLE.COM's Password:
  kadmin> quit

We can now try to acquire initial tickets for the principals to see if everything worked.

::

  eckart@dilbert:~$ kinit zaphod
  zaphod@EXAMPLE.COM's Password:

If this method returns withour error we have a success.
We can additionally list the acquired tickets:

::

  eckart@dilbert:~$ klist
  Credentials cache: FILE:/tmp/krb5cc_1000
          Principal: zaphod@EXAMPLE.COM

    Issued           Expires          Principal
  Oct 24 21:51:59  Oct 25 06:51:59  krbtgt/EXAMPLE.COM@EXAMPLE.COM

This seems correct. To remove the ticket cache simply type kdestroy.

5. Create a keytab for your service principal

::

  eckart@dilbert:~$ ktutil -k http.keytab add -p HTTP/localhost@EXAMPLE.COM -V 1 -e aes256-cts-hmac-sha1-96
  Password:
  Verifying - Password:
  eckart@dilbert:~$

This command will create a keytab file for the service principal named ``http.keytab`` in the current directory. You can specify other encryption methods than ‘aes256-cts-hmac-sha1-96’, but this is the e default encryption method for the heimdal client, so there is no additional configuration needed. You can specify other encryption types in the krb5.conf.

Note that you might need to install the unlimited strength policy files for java from here: `<http://java.sun.com/javase/downloads/index_jdk5.jsp>`_ to use the aes256 encryption from your application.

Again we can test if the keytab generation worked with the kinit command:

::

  eckart@dilbert:~$ kinit -t http.keytab HTTP/localhost@EXAMPLE.COM
  eckart@dilbert:~$ klist
  Credentials cache: FILE:/tmp/krb5cc_1000
          Principal: HTTP/localhost@EXAMPLE.COM

    Issued           Expires          Principal
  Oct 24 21:59:20  Oct 25 06:59:20  krbtgt/EXAMPLE.COM@EXAMPLE.COM

Now point the configuration of the key in 'akka.conf' to the correct location and set the correct service principal name. The web application should now startup and produce at least a 401 response with a header ``WWW-Authenticate`` = "Negotiate". The last step is to configure the browser.

6. Set up Firefox to use Kerberos/SPNEGO
This is done by typing ``about:config``. Filter the config entries for ``network.neg`` and set the config entries ``network.negotiate-auth.delegation-uris`` and ``network.negotiate-auth.trusted-uris`` to ``localhost``.
and now …

7. Access the RESTful Actor.

8. Have fun
… but acquire an initial ticket for the user principal first: ``kinit zaphod``
