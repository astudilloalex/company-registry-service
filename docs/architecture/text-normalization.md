# Text normalization contract

Normalize at the API input copy before Bean Validation; reuse the pure policy in the core. Validate canonical length/content, then compare uniqueness and compute any future fingerprint from the effective request. No generic Jackson String converter is permitted. Lengths below match PostgreSQL character limits; uppercase expansion is included before validation.

| Fields | Normalization | Validation / V1 protection |
| --- | --- | --- |
| Company `code`, Site `code` | `BusinessText.required`, outer strip + `Locale.ROOT` uppercase | required, ≤64; `ck_companies_code`, `ck_company_sites_code` |
| Company `operatingName`, Site `name` | same | required, ≤200; `ck_companies_operating_name`, `ck_company_sites_name` |
| Company `description` | `BusinessText.optional` | null allowed, provided blank rejected, ≤500; `ck_companies_description` |
| Address `countryCode` | required uppercase | exactly `[A-Z]{2}` plus provider validation; `ck_company_site_addresses_country` |
| Address `administrativeDivisionCode` | optional uppercase | ≤64 plus provider validation; `ck_company_site_addresses_division` |
| Address `addressLine1` | required uppercase | ≤300; `ck_company_site_addresses_line1` |
| Address `addressLine2` | optional uppercase | ≤300; `ck_company_site_addresses_line2` |
| Address `houseNumber`, `postalCode` | optional uppercase | ≤32; corresponding `ck_company_site_addresses_house_number`, `ck_company_site_addresses_postal_code` |
| Contact `label` | optional uppercase | ≤80; `ck_company_site_contacts_label` |
| Contact EMAIL `value` | `ContactValue`: outer strip, preserve entire mailbox case | ordinary unquoted mailbox with nonblank local/domain and dotted domain, ≤320, no whitespace/control; SQL checks trimmed/nonempty only |
| Contact PHONE/MOBILE/WHATSAPP `value` | `ContactValue`: explicit `+` country calling code, libphonenumber validity, output E.164 | no locale/default country; no extensions, vanity strings or national-only numbers; `ck_company_site_contacts_phone_format` |
| Company `logoUrl` | no uppercase or URL case rewriting; validate URL separately and require trimmed representation | ≤1000, null allowed; `ck_companies_logo_url` |
| Tenant/Party/Company/Site UUIDs | typed UUID parsing, no text normalization | external IDs need not be UUIDv7; generated IDs do |
| Actor, Process-Id, Idempotency-Key, correlation, causation, fingerprints, cryptographic material and external protocol values | preserve exact value; `OpaqueValue` checks presence/length only where applicable | boundary-specific syntax/length; never use `BusinessText` |

Optional business text is absent only when null; supplying blank is invalid. Accents and internal spacing are preserved; no transliteration, internal whitespace collapse, or Unicode normalization is performed. `strip()` removes outer Java whitespace. UUID canonical syntax and actor safety are API responsibilities; `OperationContext` retains validated process spelling exactly.

## Partial operational updates (HU02)

`CompanyInformationPatch` retains presence separately from value for `code`, `operatingName`,
`description` and `logoUrl`. An omitted property retains the stored value. A present null
clears only `description` or `logoUrl`; clearing `code` or `operatingName` is invalid.
`CompanyInformationPolicy` applies the table's rules only to present properties, after
selecting the applicable fingerprint policy. The HTTP reader must retain original text
until that selection so historical replay uses its original policy.

Limits are 64/200/500 Unicode code points for code/name/description after strip and
uppercase expansion. For example, ` ß ` becomes `SS`, and ` compañía  ágil ` becomes
`COMPAÑÍA  ÁGIL`. Thirty-two `ß` characters fit code; thirty-three do not. Supplied
blank text is invalid even for optional description. URLs retain exact case and query
content, must be absolute HTTP/HTTPS with a host, have no outer whitespace and contain
at most 1000 code points. The URL validator is the same implementation used by HU01;
creation input and creation fingerprint canonicalization are unchanged.

After applying a canonical patch, equality of all four operational values means no-op,
including `{}` and clearing an already null optional. Editing is allowed only in DRAFT,
ACTIVE and INACTIVE; ARCHIVED rejects even a no-op. No-op keeps aggregate version and
audit intact. Actor, process, keys, UUIDs and URL contents remain excluded from business
text normalization. `CompanyInformationPatchTest` and `CompanyInformationPolicyTest`
exercise presence, required clears, canonical limits, exact URLs, no-op and lifecycle.

## Partial Site information (HU05)

`SiteInformationPatch` retains original strings and independent presence for code,
name and siteType until the current or historical policy is selected.
`SiteInformationPolicy` applies `BusinessText` only to supplied code/name: strip,
Locale.ROOT uppercase, then limits of 64/200 Unicode code points. Accents and internal
spaces remain significant; uppercase expansion counts toward the limits. Present
null/blank text is rejected with the Site field rule. Present siteType accepts only
the four exact enum names and rejects null. Omitted siteType retains the current
classification, including HEADQUARTERS; HU04 registration still defaults to BRANCH.

Equality of resulting code/name/type is a PATCH no-op, including `{}`. Admission,
revision and legal headquarters protection remain separate mandatory decisions,
including for no-op. Presence remains part of the signed intent: an empty patch and
an explicit field equal to the stored value are different requests.
`SiteInformationPolicyTest` covers these information-management delta requirements;
`SiteLifecyclePolicyTest` covers lifecycle/terminality and headquarters decisions.

## Java/PostgreSQL compatibility

Java performs canonicalization; PostgreSQL CHECKs verify the stored canonical output, not an independent normalization of raw request input. Java and PostgreSQL do not promise identical Unicode/collation behavior for every raw character. Tests verify canonical accented Spanish text, Ç, Turkish-locale independence and `straße`→`STRASSE`. PostgreSQL 18 must accept that canonical output under `upper(value) = value` and `btrim(value) = value`. Deployments must use a compatible UTF-8 database locale; do not use SQL `upper(rawInput)` as the request fingerprint algorithm.

`BusinessTextTest` covers accents, spaces, optional nulls, blanks, locale independence, idempotence and expansion before length validation. `ContactValueTest` covers case-sensitive email/opaque inputs and valid/invalid/ambiguous numbers. `PostgresTextCompatibilityTest` checks actual PostgreSQL 18 uppercase/trim expressions. Full per-constraint migration coverage is tracked separately in section 5.

Telephone numbering metadata comes from pinned libphonenumber 9.0.41. It is a pure computation dependency allowed explicitly by the architecture rule; it does not add HTTP, Quarkus, persistence or Mutiny to Domain. Its resources must also be exercised by the later native verification suite.
