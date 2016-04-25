Keys for running Tls tests using the `ExampleHttpContexts`
----------------------------------------------------------

Instructions adapted from

 * http://datacenteroverlords.com/2012/03/01/creating-your-own-ssl-certificate-authority/
 * http://security.stackexchange.com/questions/9600/how-to-use-openssl-generated-keys-in-java


# Create a rootCA key:

```
openssl genrsa -out rootCA.key 2048
```

# Self-sign CA:

```
openssl req -x509 -new -nodes -key rootCA.key -days 3560 -out rootCA.crt
```

# Create server key:

```
openssl genrsa -out server.key 2048
```

# Create server CSR (you need to set the common name CN to "akka.example.org"):

```
openssl req -new -key server.key -out server.csr
```

# Create server certificate:

```
openssl x509 -req -in server.csr -CA rootCA.crt -CAkey rootCA.key -CAcreateserial -out server.crt -days 3560
```

# Create certificate chain:

```
cat server.crt rootCA.crt > chain.pem
```

# Convert certificate and key to pkcs12 (you need to provide a password manually, `ExampleHttpContexts`
# expects the password to be "abcdef"):

```
openssl pkcs12 -export -name servercrt -in chain.pem -inkey server.key -out server.p12
```

# For investigating remote certs use:

```
openssl s_client -showcerts -connect 54.173.126.144:443
```
