# Test vectors

Fictional data, generated 2026-11-09 with openssl and a small hand-written CBOR encoder. The private keys were not
kept: a new vector signed by the same IACA cannot be made, generate a new set instead.

## `mdl-device-response.hex`

ISO 18013-5 `DeviceResponse`, one `org.iso.18013.5.1.mDL` document, 11 elements in `org.iso.18013.5.1`
(text, `full-date` tag 1004, bool, nested `driving_privileges`, non-ASCII `given_name`).

Spec form everywhere, checked independently after generation:

- `IssuerSignedItemBytes` = `#6.24(bstr)`; every `valueDigests` entry is SHA-256 over those bytes, tag included.
- `issuerAuth` = untagged COSE_Sign1, protected `{1: -7}` (ES256), unprotected `{33: DS cert}`,
  payload = `bstr(#6.24(bstr(MSO)))`, signature = 64-byte raw `r||s`.
- MSO has all mandatory keys, including `deviceKeyInfo` (EC2 P-256 COSE_Key); MSO `docType` = document `docType`.
- `deviceSigned` present: `nameSpaces` = `#6.24(<<{}>>)`, `deviceSignature` = COSE_Sign1 with detached payload
  over `DeviceAuthenticationBytes` with the SessionTranscript below.

| | valid from | valid until |
|---|---|---|
| MSO `validityInfo` | 2026-11-09T18:00:20Z | 2031-11-08T18:00:20Z |
| DS cert (in x5chain) | 2026-11-09 18:00:20 UTC | 2031-11-09 18:00:20 UTC |
| IACA (`test-iaca.pem`, trust anchor) | 2026-11-09 18:00:20 UTC | 2036-11-06 18:00:20 UTC |

Certificates: IACA is CA:TRUE, pathlen 0; DS has keyUsage digitalSignature and EKU `1.0.18013.5.1.2` (mdlDS).
Not the full ISO 18013-5 Annex B profile (no CRL distribution point, issuerAltName, ...).

## `session-transcript.hex`

The SessionTranscript the device signature was made over, OpenID4VP redirect handover shape:

```
[null, null, ["OpenID4VPHandover", sha256(cbor(
    ["x509_san_dns:verifier.example.com", "mdoc-verifier-test-nonce", null, "https://verifier.example.com/response"]))]]
```
