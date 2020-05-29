#!/bin/bash

export PW=`cat password`

# Create a client certificate, under the example.com CA
keytool -genkeypair -v \
  -alias client.example.com \
  -dname "CN=client.example.com, OU=Example Org, O=Example Company, L=San Francisco, ST=California, C=US" \
  -keystore client.example.com.p12 \
  -storetype PKCS12 \
  -keypass:env PW \
  -storepass:env PW \
  -keyalg EC \
  -keysize 256 \
  -validity 3650

# Create a certificate signing request for client.example.com
keytool -certreq -v \
  -alias client.example.com \
  -keypass:env PW \
  -storepass:env PW \
  -keystore client.example.com.p12 \
  -file client.example.com.csr

# Tell exampleCA to sign the certificate.
keytool -gencert -v \
  -alias exampleca \
  -keypass:env PW \
  -storepass:env PW \
  -keystore exampleca.p12 \
  -infile client.example.com.csr \
  -outfile client.example.com.crt \
  -ext KeyUsage:critical="digitalSignature,keyEncipherment" \
  -ext EKU="clientAuth" \
  -ext SAN="DNS:client.example.com,DNS:example.com" \
  -rfc \
  -validity 3650

rm client.example.com.csr

# Tell client.example.com.p12 it can trust exampleca as a signer.
keytool -import -v \
  -alias exampleca \
  -file exampleca.crt \
  -keystore client.example.com.p12 \
  -storetype PKCS12 \
  -storepass:env PW << EOF
yes
EOF

# Import the signed certificate back into client.example.com.p12
keytool -import -v \
  -alias client.example.com \
  -file client.example.com.crt \
  -keystore client.example.com.p12 \
  -storetype PKCS12 \
  -storepass:env PW
