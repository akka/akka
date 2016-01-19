Notes on changes

- hidden "Setup" using methods on Http
- super pool to be "dead simple"
- we want to move away from Option[HttpsContext] as it's a lie, None => defaultContext anyway
- config performed in ssl-config, applying these settings done in Akka
  - e.g. NegotiateNewSession
- was: singleRequest(req, settings, context: Option[HttpsContext]) == None meant default
- default port in context is useful for starting the https server

- in WS, we'll always want to be TLS in practice. APIs use HttpsContext, but provide default one
  - if request is to "ws://" then the https is not used of course

### Server

Needs to know upfront.

**bind / bindAndHandle** 
  - has context
  - default HTTP
  - if no port given, based on Context 80/443

=> Type: ConnectionContext - based on type HTTP / HTTPS
Note: context should be obtainable Http().defaultServerHttpsContext

### Client

## connections
Needs to know upfront.

**outgoingConnection**
  - no context 

**outgoingConnectionTls**
  - needs https context
  - provides default HTTPS

**outgoingConnection**
  - no context

**newHostConnectionPoolTls**
  - needs https context

=> Tls methods provide default HTTPS config
Type: HttpsConnectionConfig on Tls methods

## request sensitive (adds TLS when needed):
Needs context "just in case", enables when request needs it.

**singleRequest**
  - has context, default HTTPS, may drop it
  
**singleWebSocketRequest**
  - has context, default HTTPS, may drop it
  
**singleWebSocketRequest**
  - needs context, "just in case"
  - provides default HTTPS
  
=> normal methods, Tls methods
=> Tls methods provide default HTTPS config
Type: HttpsConnectionConfig on Tls methods
