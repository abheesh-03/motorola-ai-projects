import os
from abc import ABC, abstractmethod

import anthropic

SYSTEM_PROMPT = (
    "You are a concise mobile AI assistant. "
    "Rules you must follow for every reply:\n"
    "- Answer the user's question directly in the first sentence.\n"
    "- Use plain text only. No Markdown of any kind.\n"
    "- Never use *, **, #, ##, `, ```, or any other Markdown syntax.\n"
    "- Do not use bullet points or numbered lists unless the user explicitly asks for a list.\n"
    "- Keep the total response to 2 to 4 short paragraphs or fewer.\n"
    "- Do not add greetings, sign-offs, or filler phrases.\n"
    "- If the answer is one sentence, one sentence is enough."
)

_MODEL = "claude-haiku-4-5-20251001"
_TIMEOUT_SECONDS = 30.0


class ProviderUnavailableError(Exception):
    """Raised when the AI provider cannot fulfill the request."""


class AIProvider(ABC):
    @abstractmethod
    async def complete(self, message: str) -> str: ...


class MockProvider(AIProvider):
    async def complete(self, message: str) -> str:
        return f"Mock response to: {message.strip()}"


class AnthropicProvider(AIProvider):
    def __init__(self) -> None:
        # Reads ANTHROPIC_API_KEY from the environment.
        # Raises KeyError if the variable is absent — fail fast on startup.
        self._client = anthropic.AsyncAnthropic(
            api_key=os.environ["ANTHROPIC_API_KEY"],
            timeout=_TIMEOUT_SECONDS,
        )

    async def complete(self, message: str) -> str:
        try:
            result = await self._client.messages.create(
                model=_MODEL,
                max_tokens=512,
                system=SYSTEM_PROMPT,
                messages=[{"role": "user", "content": message}],
            )
            return result.content[0].text
        except anthropic.APITimeoutError as exc:
            raise ProviderUnavailableError("Anthropic request timed out") from exc
        except anthropic.APIConnectionError as exc:
            raise ProviderUnavailableError("Cannot reach Anthropic API") from exc
        except anthropic.APIStatusError as exc:
            raise ProviderUnavailableError(
                f"Anthropic API returned status {exc.status_code}"
            ) from exc
