# Architecture

This document walks through the request path end to end and explains the
reasoning behind each decision — what's there, and, in a couple of cases,
what's deliberately not there.

## Request path

```
Compose UI (AssistantScreen)
   │  user types, taps Send
   ▼
AssistantViewModel.send()
   │  viewModelScope.launch { ... }
   ▼
MessageRepository (interface)
   │  implemented by AssistantRepository
   ▼
Retrofit ApiService.chat()
   │  OkHttp, JSON over HTTP
   ▼
FastAPI POST /chat
   │  validates, assigns request_id, times the call
   ▼
AIProvider (interface)
   │  AnthropicProvider in production, MockProvider in tests
   ▼
Anthropic Claude API (claude-haiku-4-5)
```

Each arrow is a boundary where errors can happen and get translated into
something the layer above can use. That translation is the main design
concern of this project — not the AI call itself, which is a single
`messages.create()` request.

## Compose → ViewModel → Repository → Retrofit → FastAPI → provider

- **`AssistantScreen` (Compose)** is a pure function of `AssistantUiState`.
  It reads `viewModel.uiState` via `collectAsStateWithLifecycle()` and calls
  `viewModel::onInputChange`, `viewModel::send`, `viewModel::clear`. It holds
  no state of its own beyond what Compose needs for recomposition.
- **`AssistantViewModel`** owns the single source of truth: a
  `MutableStateFlow<AssistantUiState>`. It depends on `MessageRepository`
  (an interface), not on `AssistantRepository` directly — the default
  constructor argument wires the real implementation, but tests pass in fakes.
- **`MessageRepository` / `AssistantRepository`** is the interface/impl split
  that makes the ViewModel testable without Retrofit, and makes the network
  error-mapping logic testable without a ViewModel. `AssistantRepositoryTest`
  exercises the mapping directly with a `FakeApiService`; `AssistantViewModelTest`
  exercises the ViewModel with fake repositories that never touch Retrofit.
- **Retrofit + OkHttp** turn the interface call into an HTTP POST, serialize
  `ChatRequest`/`ChatResponse` with Gson, and enforce timeouts (see below).
- **FastAPI `POST /chat`** validates the request, generates a `request_id`,
  times the call, and delegates to whichever `AIProvider` is injected.
- **The provider** is the only layer that knows about Anthropic specifically.

## Lifecycle and StateFlow

`uiState` is exposed as `StateFlow<AssistantUiState>`, not `LiveData` or a
raw `Flow`, because it's a single always-available "current state" value
(input text, response, loading flag, error) — a `StateFlow` naturally models
that as one replayed snapshot instead of a stream of events.

`collectAsStateWithLifecycle()` on the Compose side ties collection to the
lifecycle: the flow is only actively collected while the activity is at
least `STARTED`, so no work happens (and no state updates trigger
recomposition) while the screen is backgrounded. This is strictly better
than a plain `collectAsState()`, which would keep collecting regardless of
lifecycle state.

The `ViewModel` itself survives configuration changes (e.g. rotation) by
construction — that's the whole point of `ViewModel` — so `uiState` (and any
in-flight `viewModelScope` coroutine) is not lost on rotation. See
[interview-notes.md](interview-notes.md#what-survives-recomposition-vs-configuration-change)
for the concrete breakdown of what does and doesn't survive each kind of
Android state loss.

## Why API keys never live in the APK

`ANTHROPIC_API_KEY` is read once, server-side, in `AnthropicProvider.__init__`
via `os.environ["ANTHROPIC_API_KEY"]`. It never appears in any Kotlin file,
Gradle file, or resource — there is nothing to decompile out of the APK,
because the app has no reference to it at all. The Android app's only secret
knowledge is the backend's URL (`http://10.0.2.2:8000/`, itself only valid
for the emulator).

This is the standard reason to put a thin backend between a mobile client and
a paid third-party API: any credential embedded in a shipped app binary is
extractable (decompilation, network interception, memory dumping), so a
client should never hold a credential it isn't willing to have every install
of the app leak.

## Main thread vs. coroutines

`AssistantViewModel.send()` launches inside `viewModelScope`, which is bound
to `Dispatchers.Main.immediate` by default but immediately hands off to
suspending calls — `repository.sendMessage()` is a `suspend fun`, and
Retrofit's coroutine adapter runs the actual HTTP call on OkHttp's dispatcher
thread pool, not the main thread. The UI thread is only touched to apply
`_uiState.update { ... }` before and after the suspend point, which is cheap.

Concretely: `isLoading = true` is set (main thread), the coroutine suspends
while the network call runs (background), then the result or exception
resumes back on the main thread to apply the final state. This is why the UI
never freezes during a send, and why `AssistantViewModelTest`'s
`` `send sets isLoading true while request is in flight` `` test needs a
`StandardTestDispatcher` (instead of `Unconfined`) to actually observe the
in-between state — with an unconfined dispatcher the whole coroutine would
run to completion synchronously in the test.

## Timeouts

Two independent timeout layers exist:

- **OkHttp** (`RetrofitClient.kt`): 10s connect, 30s read, 10s write.
- **Anthropic SDK client** (`providers.py`): 30s, passed to
  `anthropic.AsyncAnthropic(timeout=...)`.

If the Anthropic call itself hangs past 30s, the SDK raises
`anthropic.APITimeoutError`, which `AnthropicProvider.complete()` catches and
re-raises as `ProviderUnavailableError` — turned into a `503` by the FastAPI
route. If instead the whole HTTP round-trip to the backend hangs (e.g. the
backend process is unreachable), OkHttp's 30s read timeout fires client-side
and `AssistantRepository` maps the resulting `SocketTimeoutException` to "The
request took too long."

Having both matters: the OkHttp timeout protects the Android app even if the
backend hangs for a reason that has nothing to do with Anthropic (e.g. the
backend process stalls); the Anthropic client's own timeout protects the
backend from hanging on a slow provider response indefinitely, so its own
error handling and logging can run instead of the request hanging past
whatever timeout OkHttp is configured with on the client that's ultimately
calling it.

## Retries and why automatic retries were intentionally not added

Neither the Android repository nor the backend provider retries a failed
call automatically. This was a deliberate choice, not an oversight:

- **Anthropic calls cost money per request.** An automatic retry loop on a
  billed API silently multiplies cost on every transient failure, which is a
  bad default for a project without any rate limiting or budget guard in
  front of it.
- **Not all failures are retry-safe to hide.** A `503` from the provider or
  a `SocketTimeoutException` on the client are exactly the kind of signal a
  user (or a future caller) should see, not one that should be silently
  swallowed behind three retries and then surfaced anyway.
- **Retry policy is a genuine design decision, not a default to reach for.**
  It needs backoff, a retry budget, and a decision about which errors are
  even retry-safe (a `422` from bad input never is; a `503` might be). None
  of that exists yet, so the honest state to document is "no retries," with
  retry-with-backoff listed explicitly as future work in the README rather
  than half-implemented here.

The current behavior on any failure is: fail fast, map the failure to a
clear message, let the user decide whether to press Send again.

## Android 17 `ACCESS_LOCAL_NETWORK` development issue

Starting at API 37 (Android 17, per this project's `targetSdk`), Android
requires the `ACCESS_LOCAL_NETWORK` runtime permission for the app to reach
addresses on the local network — which includes `10.0.2.2`, the emulator's
alias for the host machine. Without it, OkHttp's connection attempt would
fail even though the network security config already permits cleartext to
that address.

`MainActivity.kt`'s `AssistantScreen` requests this permission once on
launch, guarded by an SDK-version check:

```kotlin
if (Build.VERSION.SDK_INT >= 37 &&
    ContextCompat.checkSelfPermission(context, localNetworkPermission)
        != PackageManager.PERMISSION_GRANTED
) {
    permissionLauncher.launch(localNetworkPermission)
}
```

The permission result isn't branched on explicitly — if denied, the
subsequent OkHttp call simply fails and surfaces through the existing
`IOException`/`SocketTimeoutException` mapping in `AssistantRepository` as a
normal network error. This is a real, current Android platform behavior
this project had to handle to run on an API 37 emulator image, not a
hypothetical.

## Backend provider abstraction

`AIProvider` is an `ABC` with a single method, `complete(message: str) -> str`.
Two implementations exist:

- **`AnthropicProvider`**: the real one, wraps `anthropic.AsyncAnthropic`,
  translates the SDK's timeout/connection/status exceptions into a single
  `ProviderUnavailableError`.
- **`MockProvider`**: returns a deterministic canned string. Used by every
  backend test via `app.dependency_overrides[get_provider]`, so the test
  suite never calls the real API, never needs a key, and never costs money
  or depends on network access to pass.

`get_provider()` is a lazy singleton — `AnthropicProvider()` (which reads the
API key and would raise `KeyError` if it's missing) is only constructed on
the first real request, not at import time. That's why the test suite can
import `app.main` and override the dependency without ever needing
`ANTHROPIC_API_KEY` set in the test environment.

## Observability / request IDs

Every `/chat` call gets a fresh `uuid4` `request_id`, generated in the route
handler before the provider is called. It's:

- returned to the client in `ChatResponse.request_id`
- included in every log line for that request (success or failure)
- paired with a `latency_ms` measurement taken with `time.perf_counter()`
  around the provider call

Log lines never include the request message or the AI's response — only
metadata (`request_id`, `provider`, `status`, `latency_ms`, and on failure
`error_type`/`http_status`). This means logs are safe to read/share/store
without incidentally capturing user prompts, while still being enough to
correlate a specific client-reported failure with a specific server-side log
line and to see per-request latency.

## Failure paths

| Where | What can fail | What happens |
|---|---|---|
| Backend request validation | blank/empty `message` | `422` with `{"error": "invalid_request", ...}`, provider never called |
| Anthropic call | timeout, connection error, bad status | caught in `AnthropicProvider`, re-raised as `ProviderUnavailableError` → backend returns `503 ai_provider_unavailable`, logged at ERROR |
| Anthropic call | anything else unexpected | backend returns `500 internal_error`, logged at ERROR |
| Android → backend | socket timeout | `AssistantRepository` catches `SocketTimeoutException` → "The request took too long." |
| Android → backend | no connection / DNS / other IO | catches `IOException` → "Unable to connect. Check your network and try again." |
| Android → backend | HTTP 5xx | catches `HttpException` → "The AI service is temporarily unavailable." |
| Android → backend | HTTP 4xx (other) | catches `HttpException` → "Something went wrong. Please try again." |
| Android → backend | malformed JSON response | catches `JsonParseException` → "Received an invalid response. Please try again." |
| Anywhere else in the repository call | unexpected exception | generic "Something went wrong." fallback |

In every case, `AssistantViewModel.send()`'s `catch` block sets
`isLoading = false` and populates `error`, so the UI can never get stuck on
a spinner with no way forward.

## Battery / network tradeoffs

This app makes no attempt at battery optimization beyond what falls out
naturally from the architecture:

- No polling, no background sync, no foreground service — a network call
  only happens in direct response to a user tapping Send, so there's no
  idle battery/network cost.
- No caching or offline queueing — every send is a live round trip; there's
  no local persistence layer to keep in sync, which keeps the app simple at
  the cost of doing nothing useful without connectivity.
- No retry loop (see above) also means no wasted radio wake-ups retrying a
  request that's likely to keep failing.

For a project of this scope, "only use the network when the user asks you
to, and don't retry silently" was judged a better tradeoff than adding
caching, background sync, or retry logic that isn't otherwise justified yet.
