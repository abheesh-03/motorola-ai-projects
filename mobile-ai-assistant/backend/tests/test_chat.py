import logging

import pytest
from fastapi.testclient import TestClient

from app.main import app, get_provider
from app.providers import AIProvider, MockProvider, ProviderUnavailableError


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


class FailingProvider(AIProvider):
    """Always raises ProviderUnavailableError — used to test the 503 path."""

    async def complete(self, message: str) -> str:
        raise ProviderUnavailableError("Simulated provider failure")


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


@pytest.fixture(autouse=True)
def use_mock_provider():
    """
    Inject MockProvider for every test.
    Tests never call the real Anthropic API.
    """
    app.dependency_overrides[get_provider] = lambda: MockProvider()
    yield
    app.dependency_overrides.clear()


client = TestClient(app)


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------


def test_chat_valid_request():
    response = client.post("/chat", json={"message": "Hello there"})

    assert response.status_code == 200

    body = response.json()
    assert body["response"] == "Mock response to: Hello there"
    assert isinstance(body["request_id"], str) and body["request_id"]
    assert isinstance(body["latency_ms"], int)
    assert body["latency_ms"] >= 0


def test_chat_blank_message_rejected():
    response = client.post("/chat", json={"message": "   "})

    assert response.status_code == 422
    assert response.json()["error"] == "invalid_request"


def test_chat_empty_message_rejected():
    response = client.post("/chat", json={"message": ""})

    assert response.status_code == 422
    assert response.json()["error"] == "invalid_request"


def test_chat_provider_failure_returns_503():
    app.dependency_overrides[get_provider] = lambda: FailingProvider()

    response = client.post("/chat", json={"message": "Hello"})

    assert response.status_code == 503
    assert response.json()["detail"] == "ai_provider_unavailable"


def test_successful_request_logs_at_info(caplog):
    with caplog.at_level(logging.INFO, logger="app.main"):
        response = client.post("/chat", json={"message": "Hello there"})

    assert response.status_code == 200

    info_records = [r for r in caplog.records if r.levelno == logging.INFO and r.name == "app.main"]
    assert len(info_records) == 1
    msg = info_records[0].getMessage()
    assert "status=ok" in msg
    assert "latency_ms=" in msg
    assert "provider=" in msg


def test_provider_failure_logs_at_error(caplog):
    app.dependency_overrides[get_provider] = lambda: FailingProvider()

    with caplog.at_level(logging.ERROR, logger="app.main"):
        response = client.post("/chat", json={"message": "Hello"})

    assert response.status_code == 503

    error_records = [r for r in caplog.records if r.levelno == logging.ERROR and r.name == "app.main"]
    assert len(error_records) == 1
    msg = error_records[0].getMessage()
    assert "status=error" in msg
    assert "http_status=503" in msg
    assert "error_type=" in msg
