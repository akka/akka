# Testing Resources

This folder contains a diverse set of keys, certificates and keystores generated using scripts inspired by 
those from https://github.com/playframework/play-samples/tree/2.8.x/play-scala-tls-example/scripts. 

The resources in this folder are build with a few restrictions. See `TlsResourcesSpec.scala` for some 
tests asserting the certificates and keys in this folder fulfill the required restrictions.  

## Client example.com key

`gen-node.example.com.sh` creates an RSA key with a certificate (emitted by example CA) that may be used 
for server authentication and client authentication. That's useful in peer-to-peer setups like Akka remote 
connections were a node is both a server and a client. It also exports the key to PEM.   

`gen-client.example.com.sh ` creates an EC key with a certificate (emitted by example CA) that may be used
for client authentication only. The certificate is issued to `client.example.com` and includes multiple 
subject alternatives. 

`gen-example.com.sh` creates an EC key with a certificate (emitted by example CA) that may be used for server 
 authentication only. The certificate is issued to `example.com` and includes multiple subject alternatives. 
 
`gen-one.example.com.sh` creates an EC key with a certificate (emitted by example CA) that may be used for 
server authentication only. The certificate is issued to `one.example.com` and includes multiple subject alternatives. 
 
`gen-two.example.com.sh` creates an EC key with a certificate (emitted by example CA) that may be used for 
server authentication only. The certificate is issued to `two.example.com` and includes multiple subject alternatives. 
 
All certificates for the `example.com` domain except `island.example.com` include `example.com` as a
Subject Alternate Name. This means `island.example.com` can't pass a `PeerSubjectVerifier` verification. 