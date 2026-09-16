# Interview notes

Short, natural answers to questions this project is likely to prompt. Written
to say out loud, not to read from. See [architecture.md](architecture.md) for
the full detail behind any of these.

### What did you build?

A small end-to-end AI assistant: an Android app built with Jetpack Compose
that sends a prompt to a FastAPI backend I also wrote, which calls Anthropic's
Claude API and returns the reply. It's not just a UI demo or just a backend
demo — the whole path from tapping "Send" on a phone to getting a real model
response back is wired up and tested, including the failure cases.

### Why FastAPI in the middle? Why not call Anthropic directly from the app?

Two reasons, and the security one is the real one. First, the API key: if the
Android app called Anthropic directly, the key would have to live in the APK,
and anything shipped in an APK can be extracted — decompiled, sniffed off the
wire, pulled from memory. A backend keeps the key server-side where it's
never in a distributable artifact. Second, it gives me a place to do things
that don't belong on the client — request IDs, latency logging, and later,
rate limiting or swapping providers — without touching the app.

### Why ViewModel + StateFlow?

I wanted one source of truth for the screen — input text, response, loading,
error — that survives configuration changes like rotation and that Compose
can collect safely. `ViewModel` gives me the survival; `StateFlow` gives me a
single current-value stream that's a natural fit for "here's the state of
the screen right now," as opposed to `SharedFlow` or callbacks, which are
better suited to one-off events.

### Why a Repository?

Mainly for testability and separation of concerns. `MessageRepository` is an
interface; `AssistantRepository` is the real Retrofit-backed implementation.
That split let me test the ViewModel with a fake repository that has zero
knowledge of networking, and separately test the repository's error-mapping
logic with a fake `ApiService` that has zero knowledge of the ViewModel. If I
ever swapped Retrofit for something else, the ViewModel wouldn't need to
change at all.

### Why Retrofit/OkHttp?

It's the standard, well-supported choice for typed HTTP calls on Android —
coroutine support out of the box, a converter for JSON (Gson here), and
configurable timeouts at the OkHttp layer. For a single POST endpoint I
could've hand-rolled it with raw OkHttp, but Retrofit's interface-based API
call (`suspend fun chat(...)`) is exactly the shape I wanted the repository
to depend on.

### Why 10.0.2.2?

That's not a real internet address — it's a special alias the Android
emulator's virtual network provides that forwards to the host machine's
loopback interface. Inside the emulator, `localhost` means the emulator
itself, not my Mac, so there has to be some other way to say "the machine
running the emulator." `10.0.2.2` is that. It only works from the emulator to
the host — it's meaningless on a physical device or in any real deployment,
which is part of why this project is explicitly emulator-only right now.

### Why no API key in Android?

Covered above, but the short version: anything in the app is extractable, so
the app should never hold a credential I'm not willing to have every
installed copy leak. The Android app doesn't know Anthropic exists — it only
knows how to talk to my backend.

### How do you avoid freezing the UI?

The network call is a suspend function, and `viewModelScope.launch` only
touches the main thread to apply state updates before and after the
suspension point — `_uiState.update { isLoading = true }`, then later the
result. The actual HTTP call runs on OkHttp's own thread pool via Retrofit's
coroutine support, not on the main thread. So the UI thread is free the
entire time the request is in flight, and Compose just reacts to the
`isLoading` flag by showing a spinner.

### What happens when the backend or network fails?

The repository catches specific exception types — socket timeout, other IO
errors, HTTP error codes, malformed JSON — and maps each one to a
plain-language message instead of letting a raw exception or stack trace
reach the UI. The ViewModel puts that message into `uiState.error` and always
clears `isLoading`, so the user sees a clear message and the app is never
stuck spinning. On the backend side, I distinguish "the AI provider itself
failed" (503, since that's a signal the service is temporarily unavailable)
from "something else went wrong internally" (500), and log both with enough
metadata to debug later.

### Why no automatic retries?

Because Anthropic calls cost money, and a retry loop is exactly the kind of
thing that quietly turns one failed request into three billed ones. I also
didn't want to hide a real signal — like a 503 or a timeout — behind retries
without a deliberate policy for backoff and which errors are even safe to
retry. Rather than half-implement that, I documented it as a real,
intentional gap and listed retry-with-backoff as future work.

### What survives recomposition vs. configuration change?

Recomposition (e.g. Compose re-running `AssistantScreen` because state
changed) doesn't lose anything — `uiState` lives in the ViewModel, not in a
`remember {}` block, so recomposition just re-reads the current value.
Configuration change (e.g. rotation) also doesn't lose the ViewModel itself —
that's the entire point of `ViewModel`, it's retained across configuration
changes by the framework — so `uiState` and any in-flight coroutine in
`viewModelScope` survive rotation. What wouldn't survive is process death
(e.g. the OS kills the app in the background under memory pressure) — there's
no `SavedStateHandle` usage here, so a fully killed-and-restored process
would come back to a blank `AssistantUiState()`. That's a real, current
limitation, not something I've papered over.

### What would you improve for production?

Remove the `10.0.2.2` cleartext exception and switch to HTTPS with a real
backend URL; add auth between the app and the backend; add conversation
history instead of stateless single turns; add retry-with-backoff behind an
actual policy; add rate limiting in front of the Anthropic calls; support
multiple backend environments via build variants instead of one hardcoded
URL; and add instrumented UI tests alongside the unit tests I already have.

### What metrics would you track?

I already emit the basics — per-request latency and success/failure status,
tagged with a request ID and provider name, in structured log lines. In a
real deployment I'd want that aggregated: p50/p95/p99 latency, error rate by
type (timeout vs. 5xx vs. validation), and request volume over time, plus
being able to trace a specific client-reported failure back to its backend
log line via the request ID that's already returned to the client today.

### What did you actually test?

On Android: the ViewModel (input handling, clear, success path, error path,
and — using a controlled test dispatcher — that `isLoading` is actually true
while a request is in flight) and the repository's exception-to-message
mapping, using fake repositories/API services with no real networking
involved. 18 tests total. On the backend: the `/chat` endpoint's happy path,
blank/empty message validation, the 503 path when the provider fails, and
that both success and failure are logged with the right structured fields —
6 tests, all running against `MockProvider` so the real Anthropic API is
never called in CI. What I have not done: instrumented Espresso UI tests, and
I haven't load-tested or benchmarked latency beyond what I've observed
manually running it — I'm not going to make up numbers I don't have.
