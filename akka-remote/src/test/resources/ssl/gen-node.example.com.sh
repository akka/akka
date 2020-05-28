#!/bin/bash

export PW=`cat password`

# Create a certificate used for peer-to-peer, under the example.com CA
# Uses a 10 year validity to simplify maintenance. Consider what validity is more convenient
# for your use case. Uses RSA/2048 because we'll export the private key to PEM an use
# akka-pki to read it (akka-pki only supports RSA key formats ATM).
keytool -genkeypair -v \
  -alias node.example.com \
  -dname "CN=node.example.com, OU=Example Org, O=Example Company, L=San Francisco, ST=California, C=US" \
  -keystore node.example.com.p12 \
  -keypass:env PW \
  -storepass:env PW \
  -storetype PKCS12 \
  -keyalg RSA \
  -keysize 2048 \
  -validity 3650

# Create a certificate signing request for node.example.com
keytool -certreq -v \
  -alias node.example.com \
  -keypass:env PW \
  -storepass:env PW \
  -keystore node.example.com.p12 \
  -file node.example.com.csr

# Tell exampleCA to sign the certificate.
keytool -gencert -v \
  -alias exampleca \
  -keypass:env PW \
  -storepass:env PW \
  -keystore exampleca.p12 \
  -infile node.example.com.csr \
  -outfile node.example.com.crt \
  -ext KeyUsage:critical="digitalSignature,keyEncipherment" \
  -ext EKU="serverAuth,clientAuth" \
  -ext SAN="DNS:node.example.com,DNS:example.com" \
  -rfc \
  -validity 3650

keytool -import -v \
  -alias exampleca \
  -file exampleca.crt \
  -keystore node.example.com.p12 \
  -storetype PKCS12 \
  -storepass:env PW << EOF
yes
EOF

# Import the signed certificate back into example.com.p12
keytool -import -v \
  -alias node.example.com \
  -file node.example.com.crt \
  -keystore node.example.com.p12 \
  -storetype PKCS12 \
  -storepass:env PW

# Export the private key as a PEM. This export adds some extra PKCS#12 bag info.
openssl pkcs12 -in node.example.com.p12 -nodes -nocerts -out tmp.pem -passin pass:kLnCu3rboe
# A second pass extracts the PKCS#12 bag information and produces a clean PEM file.
openssl rsa -in tmp.pem -out node.example.com.pem


rm -rf *.csr
rm tmp.pem