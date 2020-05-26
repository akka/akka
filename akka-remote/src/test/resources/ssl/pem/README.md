# PEM resources

The following resources were copy/pasted from the akka-pki/src/test/resources folder. 

- multi-prime-pkcs1.pem
- pkcs1.pem
- pkcs8.pem
- selfsigned-certificate.pem

Note that these set of certificates are valid PEMs but are invalid for akka-remote
use. Either the key length, certificate usage limitations (via the UsageKeyExtensions), 
or the fact that the key's certificate is self-signed cause one of the following 
errors: `certificate_unknown`, `certificate verify message signature error`/`bad_certificate`
during the SSLHandshake.
