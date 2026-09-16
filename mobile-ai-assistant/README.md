# Mobile AI Assistant

A small end-to-end mobile AI assistant: a native Android (Jetpack Compose) client
talks to a FastAPI backend, which forwards prompts to Anthropic's Claude API and
returns the reply. Built as a focused, working example of the full stack — UI
state management, networking, error handling, a swappable backend provider
layer, and tests on both sides.

This is a personal/portfolio project. It runs locally against the Android
emulator and a FastAPI dev server; it has not been deployed anywhere or
published to the Play Store.

## Features

- Single-screen chat-style UI: type a prompt, send it, see the reply
- Loading, success, and error states, all driven by one `StateFlow`
- "Clear" resets the conversation back to a blank state
- Errors are mapped to plain-language messages (timeout, no connection, server
  error, malformed response) instead of raw exception text
- Backend assigns a request ID and measures latency for every call
- Backend logs structured request/response metadata — never prompt or
  response contents
- AI provider is abstracted behind an interface, so the real Anthropic call
  can be swapped for a mock in tests
- 18 Android unit tests (ViewModel + Repository) and 6 backend pytest tests,
  all passing

## Architecture

```
┌─────────────────────────────┐
│         Android App          │
│                               │
│  Compose UI (AssistantScreen)│
│           │ collectAsStateWithLifecycle
│           ▼                  │
│  AssistantViewModel           │
│   (StateFlow<AssistantUiState>)│
│           │                  │
│           ▼                  │
│  MessageRepository (interface)│
│           │                  │
│           ▼                  │
│  AssistantRepository          │
│   (maps exceptions → messages)│
│           │                  │
│           ▼                  │
│  Retrofit + OkHttp             │
└───────────────┬───────────────┘
                │ HTTP POST /chat
                │ (10.0.2.2:8000, emulator → host)
                ▼
┌─────────────────────────────┐
│      FastAPI Backend          │
│                               │
│  POST /chat                   │
│   - validates request         │
│   - assigns request_id (uuid4)│
│   - times the call            │
│   - logs structured metadata  │
│           │                  │
│           ▼                  │
│  AIProvider (interface)        │
│   - AnthropicProvider (real)   │
│   - MockProvider (tests)       │
└───────────────┬───────────────┘
                │
                ▼
        Anthropic Claude API
```

See [docs/architecture.md](docs/architecture.md) for the reasoning behind each
layer.

## Tech stack

**Android**
- Kotlin, Jetpack Compose, Material 3
- ViewModel + `StateFlow`, lifecycle-aware collection (`collectAsStateWithLifecycle`)
- Retrofit 2.11 + OkHttp 4.12 (Gson converter)
- `minSdk` 26, `targetSdk`/`compileSdk` 37
- JUnit4 + `kotlinx-coroutines-test`

**Backend**
- Python, FastAPI, Uvicorn
- Anthropic Python SDK (`claude-haiku-4-5-20251001`)
- pytest + FastAPI `TestClient`

## Repository layout

```
mobile-ai-assistant/
├── android/    # Jetpack Compose client
├── backend/    # FastAPI service
└── docs/       # architecture.md, interview-notes.md
```

## Android setup

1. Open `android/` in Android Studio (or use `gradlew` directly from that
   directory).
2. Run on an emulator — the app is hardcoded to call the backend at
   `http://10.0.2.2:8000/` (see [why 10.0.2.2](#why-the-emulator-uses-1002),
   below). It has not been wired up for a physical device or a real host
   address.
3. Build/install:

   ```bash
   cd android
   ./gradlew installDebug
   ```

On first launch, on Android 17 (API 37) and above, the app requests the
`ACCESS_LOCAL_NETWORK` runtime permission so it's allowed to reach
`10.0.2.2`. See [docs/architecture.md](docs/architecture.md) for details.

## Backend setup

```bash
cd backend
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

### Setting `ANTHROPIC_API_KEY` safely

The backend reads the key from the environment (`os.environ["ANTHROPIC_API_KEY"]`
in `app/providers.py`) and fails fast on startup if it's missing. The key is
**never** sent to, stored in, or referenced by the Android app or the APK —
the client only ever talks to your own backend.

1. Copy the example file and fill in your real key:

   ```bash
   cp backend/.env.example backend/.env
   ```

2. `backend/.env` is not loaded automatically (no `python-dotenv` dependency
   is installed), so export it into your shell before running the server:

   ```bash
   export $(grep -v '^#' backend/.env | xargs)
   ```

   or just set it directly:

   ```bash
   export ANTHROPIC_API_KEY=your_key_here
   ```

3. **Never commit `backend/.env` or paste a real key into any file in this
   repo.** `.env` is gitignored; `.env.example` is the only file meant to be
   committed, and it contains a placeholder only.

### Running the backend

```bash
cd backend
uvicorn app.main:app --reload
```

This binds to `127.0.0.1:8000` by default, which the Android emulator can
reach at `10.0.2.2:8000`.

### Why the emulator uses 10.0.2.2

The Android emulator runs in its own virtual network and can't resolve
`localhost`/`127.0.0.1` as "the host machine" — inside the emulator,
`localhost` means the emulator itself. `10.0.2.2` is a special alias the
emulator's virtual router provides that forwards to the host machine's
loopback interface. It's hardcoded in `RetrofitClient.kt` and only works for
emulator-to-host traffic; it is not a real, routable address and won't work
on a physical device or in any deployed environment.

## Testing

**Android** (from `android/`):

```bash
./gradlew test
```

Runs the JVM unit tests — `AssistantViewModelTest` and
`AssistantRepositoryTest` — plus the boilerplate `ExampleUnitTest` (18 tests
total). These use fake repositories/API services, not real network calls, and
run in milliseconds.

**Backend** (from `backend/`, with the virtualenv active):

```bash
pytest
```

Runs 6 tests against a `MockProvider` (via FastAPI's
`app.dependency_overrides`) — the real Anthropic API is never called during
tests.

## Security decisions

- **API key stays server-side only.** It lives in the backend's environment
  and is never embedded in, shipped with, or reachable from the Android app.
- **Cleartext traffic is restricted to one address.** `network_security_config.xml`
  allows cleartext HTTP only to `10.0.2.2` (the emulator-to-host alias); all
  other cleartext traffic is blocked by Android's default HTTPS-only policy.
  This is a deliberate, narrowly-scoped exception for local development, not
  a general cleartext allowance.
- **Prompt and response contents are never logged.** The backend logs
  `request_id`, provider name, status, latency, and (on failure) the
  exception type/HTTP status — not the message text or the AI's reply.
- **Request validation.** The backend rejects blank/empty messages with a
  422 before any call reaches the AI provider.
- **Provider abstraction limits blast radius.** All backend tests run against
  `MockProvider`; the real `AnthropicProvider` (and the API key it needs) is
  never touched in CI/test runs.

## Error handling

The backend distinguishes provider failures (timeout, connection error, bad
status from Anthropic) from unexpected internal errors, returning `503` for
the former and `500` for the latter, and logs both with structured metadata.

The Android repository layer (`AssistantRepository`) catches specific
exception types and maps each to a plain-language message before it ever
reaches the UI:

| Exception | User-facing message |
|---|---|
| `SocketTimeoutException` | "The request took too long. Please try again." |
| `IOException` (other) | "Unable to connect. Check your network and try again." |
| `HttpException`, 5xx | "The AI service is temporarily unavailable. Please try again." |
| `HttpException`, other | "Something went wrong. Please try again." |
| `JsonParseException` | "Received an invalid response. Please try again." |
| anything else | "Something went wrong. Please try again." |

The ViewModel surfaces that message in `AssistantUiState.error` and always
clears `isLoading`, so the UI never gets stuck spinning.

## Limitations

- Local development only — no deployment, no Docker, no HTTPS, no Play Store
  listing.
- Single hardcoded backend URL (`10.0.2.2:8000`); no build variants or
  runtime configuration for different environments or devices.
- No authentication/authorization between the app and the backend.
- No conversation history — each send is a single, independent request; the
  backend and the Anthropic call are both stateless per request.
- No automatic retries on failure (see [docs/architecture.md](docs/architecture.md)
  for why).
- No on-device or offline inference — every request depends on network
  connectivity and the Anthropic API being reachable.
- No instrumented (Espresso) UI tests are part of the verified suite; test
  coverage is unit-level (ViewModel/Repository on Android, endpoint-level on
  the backend).

## Future improvements

- Containerize the backend and document a real deployment path (this would be
  new work, not something already done)
- Add HTTPS and remove the emulator-only cleartext exception
- Add conversation history / multi-turn context
- Add request-level auth between the app and backend
- Add Espresso/UI tests on Android
- Add basic rate limiting and retry-with-backoff at the network layer
- Support configurable backend URLs (per build variant) for real-device testing

---

**Note:** the backend as configured (cleartext traffic to `10.0.2.2`, no
auth, no HTTPS, single-process `uvicorn --reload`) is a **local-development
setup only**. None of these settings are appropriate for a production or
publicly reachable deployment.
