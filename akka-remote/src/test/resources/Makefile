all: truststore keystore

truststore: domain.crt
	keytool -importcert -file domain.crt -keystore truststore -deststorepass changeme

keystore: domain.crt domain.key
	openssl pkcs12 -export -inkey domain.key -passin pass:changeme -in domain.crt -out keystore -passout pass:changeme

domain.crt: domain.csr domain.key
	openssl x509 -req -in domain.csr -sha256 -extfile <(cat /etc/ssl/openssl.cnf <(printf "[SAN]\nsubjectAltName=DNS:localhost")) -out domain.crt -extensions SAN -signkey domain.key

domain.csr:
	openssl req -new -sha256 -key domain.key -subj "/C=ZA/ST=web/O=Lightbend/CN=akka-remote" -reqexts SAN -config <(cat /etc/ssl/openssl.cnf <(printf "[SAN]\nsubjectAltName=DNS:localhost")) -out domain.csr -passout pass:changeme

.PHONY: clean
clean:
	rm domain.crt domain.csr keystore truststore
