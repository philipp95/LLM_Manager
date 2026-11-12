---
name: android-secure-credentials
description: >
  Use when storing, reading, rotating, or deleting user-supplied secrets in an
  Android app — LLM provider API keys, gateway tokens, OAuth refresh tokens,
  device private keys. Covers Android Keystore AES-GCM with DataStore, why
  EncryptedSharedPreferences and androidx.security:security-crypto are deprecated,
  host-scoped auth headers so a key is never sent to the wrong provider, backup
  and autofill exclusion, log/crash redaction, biometric gating, and key
  invalidation. Triggers on API key, token storage, Keystore, KeyGenParameterSpec,
  EncryptedSharedPreferences, MasterKey, Tink, "where do I put the secret",
  or any auth Interceptor that attaches a credential.
---

# Secure credentials on Android

This app's entire job is holding other people's LLM provider keys. Those keys bill
real money and are exfiltration targets. Storage is a first-class feature here, not
plumbing.

## Do not use `androidx.security:security-crypto`

`EncryptedSharedPreferences`, `EncryptedFile`, and `MasterKey` are **deprecated** —
all APIs in the library were deprecated as of `security-crypto` 1.1.0-alpha07
(April 2025), carried through the 1.1.0 stable release, "in favour of existing
platform APIs and direct use of Android Keystore."

Reasons that matter for a new app: inconsistent Keystore behaviour across OEMs,
fragile AES-GCM support on some devices, and **no clean migration path once a
scheme is deployed**. Do not start a greenfield app on it.

The current stack is:

- **Android Keystore** — protects the key (hardware-backed where available)
- **AES-256-GCM** — encrypts the value
- **DataStore** — persists the ciphertext
- **Tink** — optional, for a consistent and *upgradeable* crypto layer that is
  independent of device-specific Keystore quirks

Reach for Tink when you need versioned, rotatable key material. Direct Keystore
AES-GCM is fine when the app holds a handful of user-entered strings.

## The shape

Generate once, in the Keystore, non-exportable:

```kotlin
private fun getOrCreateKey(alias: String): SecretKey {
    val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
        init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // .setUserAuthenticationRequired(true)  // see Biometric gating
                .build()
        )
    }.generateKey()
}
```

Encrypt, and **store the GCM IV alongside the ciphertext** — it is generated per
operation and is not secret, but decryption is impossible without it:

```kotlin
fun encrypt(plain: ByteArray, alias: String): Pair<ByteArray, ByteArray> {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(alias))
    return cipher.iv to cipher.doFinal(plain)   // iv, ciphertext
}

fun decrypt(iv: ByteArray, ciphertext: ByteArray, alias: String): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(alias), GCMParameterSpec(128, iv))
    return cipher.doFinal(ciphertext)
}
```

**Never reuse an IV with the same key.** Let `Cipher` generate it; do not supply a
fixed or counter-based IV.

Persist the `(iv, ciphertext)` pair in DataStore as Base64 or a Proto `bytes` field.
Plain DataStore is *not* encrypted storage — it is where the ciphertext lives.

## One key alias per provider

Give each provider its own alias (`key.anthropic`, `key.openai`, `key.openclaw`).
This makes "delete my OpenAI key" a real key deletion rather than a rewrite of a
shared blob, contains the blast radius of a decryption failure, and makes rotation
per-provider.

## Host-scope the auth header — the highest-severity bug in this app class

An OkHttp `Interceptor` that attaches `Authorization` to every request will happily
send the user's Anthropic key to whatever host the request goes to. With
user-configurable base URLs — which a model manager has by definition — that is
credential exfiltration triggered by a typo.

```kotlin
class ProviderAuthInterceptor(
    private val credentials: CredentialStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // Resolve by the *actual* host being contacted, not by a field set at construction.
        val provider = credentials.providerForHost(request.url.host)
            ?: return chain.proceed(request)   // unknown host: send nothing

        val token = credentials.token(provider)
            ?: return chain.proceed(request)

        return chain.proceed(
            request.newBuilder()
                .header(provider.authHeaderName, provider.authHeaderValue(token))
                .build()
        )
    }
}
```

Rules:

- Resolve the credential **from `request.url.host` at call time**. Never bind a key
  to a client instance and reuse the client.
- Unknown host → attach nothing. Fail closed.
- Re-check the host **after redirects**. An HTTP redirect to another origin must not
  carry the header; strip it or disable cross-host redirects on the credentialed
  client.
- Require HTTPS for any request carrying a key. Do not allow a user-entered
  `http://` base URL to receive a credential.

## Keep secrets out of everything that leaves the device

- **Backup / transfer.** `android:allowBackup="false"` alone is not enough on
  Android 12+: cloud backup and **device-to-device transfer are separate channels**.
  Use `android:dataExtractionRules` with explicit `<cloud-backup>` *and*
  `<device-transfer>` sections excluding the credential DataStore (keep
  `fullBackupContent` for API < 31). Keystore keys are device-bound and never
  leave, so anything restored elsewhere is undecryptable ciphertext — exclude it so
  users get a clean "re-enter your key" rather than a corrupt-state bug.
- **Logs.** Never log a key, a full request URL that may carry one, or raw headers.
  Configure OkHttp's `HttpLoggingInterceptor` with `redactHeader("Authorization")`
  and `redactHeader("x-api-key")`, and keep it at `BODY` only in debug builds —
  streamed completion bodies otherwise land in logcat.
- **Crash reporting.** Scrub before upload. Exception messages from HTTP clients
  frequently embed the URL.
- **Screenshots.** Add `FLAG_SECURE` to any screen that displays a key in
  plaintext, and prefer showing a masked value with a deliberate reveal action.
- **Clipboard.** If offering "copy key", mark the clip sensitive
  (`ClipDescription.EXTRA_IS_SENSITIVE`) so the system suppresses the content
  preview. Treat that as a UI hint, not a guarantee — it does not promise exclusion
  from every clipboard manager or history implementation. Prefer not offering
  "copy key" at all.
- **Autofill.** Mark key entry fields `importantForAutofill="no"` — they are not
  passwords and should not be saved by a password manager as one.

## Biometric gating

`setUserAuthenticationRequired(true)` on its own makes the key **per-operation**:
every single use must be authorised through a `BiometricPrompt` with a
`CryptoObject` wrapping the initialised `Cipher`. There is no ambient "recently
unlocked" grace unless you opt into one with
`setUserAuthenticationParameters(validitySeconds, …)` (or the deprecated
`setUserAuthenticationValidityDurationSeconds`), which makes the key time-bound
instead.

Decide which you want deliberately — per-operation is stronger but forces a prompt
on every request, which is wrong for a chat app sending many completions.

Use it for *user-initiated* actions. Do not use it for a credential background sync
needs — you will get `UserNotAuthenticatedException` at an unrecoverable moment. If both are needed, use two aliases: a gated one for
"send my prompt" and an ungated one for background refresh with narrower scope.

## Invalidation — plan for it before shipping

**Auth-bound keys** — those built with `setUserAuthenticationRequired(true)` — are
permanently invalidated when the secure lock screen is **removed or reset**, and
(with `setInvalidatedByBiometricEnrollment`, which defaults to true) when a new
biometric is enrolled. Decryption then throws
`KeyPermanentlyInvalidatedException`.

A key *without* `setUserAuthenticationRequired` is not tied to the lock screen and
survives an ordinary PIN change. Uninstalling the app destroys its keys either way.

Handle it explicitly: catch it, delete the now-useless ciphertext and the alias,
and prompt the user to re-enter the key. An app that crash-loops on a stale key
after the user changed their PIN is a support nightmare, and it is the single most
common Keystore bug.

Also implement, because users will ask:

- **Rotate** — re-encrypt under a fresh alias, then delete the old one.
- **Delete** — `keyStore.deleteEntry(alias)` *and* clear the DataStore record. One
  without the other leaves either orphaned ciphertext or an undeletable key.
- **Wipe all** on logout/reset.

## Never in source

No keys in `strings.xml`, `BuildConfig`, `gradle.properties`, committed
`local.properties`, or version control. These are *user-supplied* secrets in this
app — the app should ship with none of its own. If a first-party key ever becomes
necessary, it belongs behind a backend, not in the APK: anything shipped in an APK
is extractable.

## Checklist

- [ ] No `androidx.security:security-crypto` dependency
- [ ] Key generated in `AndroidKeyStore`, 256-bit, GCM, never exported
- [ ] Fresh IV per encryption, stored with the ciphertext
- [ ] One alias per provider
- [ ] Auth header resolved from the live request host; fails closed; stripped on
      cross-host redirect; HTTPS enforced
- [ ] Backup/data-extraction excludes the credential store
- [ ] `Authorization` / `x-api-key` redacted in logging; body logging debug-only
- [ ] `KeyPermanentlyInvalidatedException` handled with a re-entry flow
- [ ] Rotate, delete-one, and wipe-all implemented and tested
