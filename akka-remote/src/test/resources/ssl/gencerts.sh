#!/bin/bash

export PW=`cat password`

. gen-functions.sh

rm *.crt
rm *.p12
rm *.pem

./genca.sh

## some server certificates
createExampleECKeySet "one" "serverAuth" "DNS:one.example.com,DNS:example.com"
createExampleECKeySet "two" "serverAuth" "DNS:two.example.com,DNS:example.com"
createExampleECKeySet "island" "serverAuth" "DNS:island.example.com"

## a client certificate
createExampleECKeySet "client" "clientAuth" "DNS:client.example.com,DNS:example.com"

## node.example.com is part of the example.com dataset (in ./ssl/ folder) but not the artery-nodes
createExampleRSAKeySet "node" "serverAuth,clientAuth" "DNS:node.example.com,DNS:example.com"
createExampleRSAKeySet "rsa-client" "clientAuth" "DNS:rsa-client.example.com,DNS:example.com"

## a certificate valid for both server and client (peer-to-peer)
## with RSA keys
./gen-artery-nodes.example.com.sh
