#!/bin/bash

export PW=`cat password`

# Create a server certificate, tied to example.com
keytool -genkeypair -v \
  -alias example.com \
  -dname "CN=example.com, OU=Example Org, O=Example Company, L=San Francisco, ST=California, C=US" \
  -keystore example.com.p12 \
  -storetype PKCS12 \
  -keypass:env PW \
  -storepass:env PW \
  -keyalg EC \
  -keysize 256 \
  -validity 3650

# Create a certificate signing request for example.com
keytool -certreq -v \
  -alias example.com \
  -keypass:env PW \
  -storepass:env PW \
  -keystore example.com.p12 \
  -file example.com.csr

# Tell exampleCA to sign the example.com certificate. 
keytool -gencert -v \
  -alias exampleca \
  -keypass:env PW \
  -storepass:env PW \
  -keystore exampleca.p12 \
  -infile example.com.csr \
  -outfile example.com.crt \
  -ext KeyUsage:critical="digitalSignature,keyEncipherment" \
  -ext EKU="serverAuth" \
  -ext SAN="DNS:the.example.com,DNS:example.com" \
  -rfc \
  -validity 3650

rm example.com.csr

# Tell example.com.p12 it can trust exampleca as a signer.
keytool -import -v \
  -alias exampleca \
  -file exampleca.crt \
  -keystore example.com.p12 \
  -storetype PKCS12 \
  -storepass:env PW << EOF
yes
EOF

# Import the signed certificate back into example.com.p12 
keytool -import -v \
  -alias example.com \
  -file example.com.crt \
  -keystore example.com.p12 \
  -storetype PKCS12 \
  -storepass:env PW
