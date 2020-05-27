# Testing Resources

This folder contains a diverse set of certificates and keystores copied 
from https://github.com/playframework/play-samples/tree/2.8.x/play-scala-tls-example/scripts. Refer to 
that project for instructions and scripts to create the files here.

By default, the private keys are created using the `EC` algorithm and a 56-bit size. 

## Client example.com key

`gen-client.example.com.sh` uses the example-ca to emit a client-only certificate under `client.example.com`. 

`gen-node.example.com.sh` uses the example-ca to emit a certificate that may be used for server 
authentication and client authentication. That's useful in peer-to-peer setups like Akka remote connections
were a node is both server and a client.   

Unlike all other `gen-xxx.example.com.sh` the `gen-node.example.com.sh` doesn't use the `EC` algorithm 
and instead it uses `RSA`. On top of that, it export the private key from the PKCS#12 bag into 
`node.example.com.pem` which can be used by `akka-pki`'s key loader. 
