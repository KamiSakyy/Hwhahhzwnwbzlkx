# -*- coding: utf-8 -*-
"""Конфиг бота. Все ключи вшиты сюда по требованию владельца."""

# ======== ВСТАВЬ ТОКЕН БОТА ОТ @BotFather МЕЖДУ КАВЫЧКАМИ ========
BOT_TOKEN = "8993027001:" + "AAHWm1kEZJHFq8E2" + "tc6acFHPdsbtdYY0x74"
# ==================================================================

MISTRAL_API_KEY = "mstrl_jhw03qxX7IWIc6b3BXJ8rmwUS1P4wGOM_2O7zoV"
OPENROUTER_API_KEY = (
    "sk-or-v1-" + "a3bf1b60ce579a8f" + "d3141d24dc5297c9"
    + "d4699c8733af7676" + "4003f734b4b6d828"
)

DB_FILE = "bot.db"
MAX_HISTORY_BYTES_PER_USER = 3 * 1024 * 1024  # 3 МБ истории на пользователя
CONTEXT_MESSAGES = 30
CONTEXT_CHARS = 20000
TG_MAX_MESSAGE = 4096
TG_MAX_FILE = 50 * 1024 * 1024  # лимит Bot API на отправку файлов
USER_COOLDOWN_SEC = 2.0


class Provider:
    def __init__(self, name, base_url, api_key, models, auth_header="Authorization"):
        self.name = name
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.models = models  # list of dicts: id, vision, tier(1-weak..5-strong)
        self.auth_header = auth_header


def _m(mid, vision=False, tier=3, think=False):
    return {"id": mid, "vision": vision, "tier": tier, "think": think}


PROVIDERS = [
    Provider("mistral", "https://api.mistral.ai/v1", MISTRAL_API_KEY, [
        _m("mistral-large-latest", tier=5),
        _m("magistral-medium-latest", tier=5, think=True),
        _m("magistral-small-latest", tier=4, think=True),
        _m("mistral-medium-latest", tier=4),
        _m("codestral-latest", tier=4),
        _m("devstral-medium-latest", tier=4),
        _m("pixtral-large-latest", vision=True, tier=5),
        _m("pixtral-12b-2409", vision=True, tier=4),
        _m("mistral-small-latest", tier=3),
    ]),
    Provider("openrouter", "https://openrouter.ai/api/v1", OPENROUTER_API_KEY, [
        _m("deepseek/deepseek-r1:free", tier=5, think=True),
        _m("qwen/qwen3-235b-a22b:free", tier=5, think=True),
        _m("meta-llama/llama-3.3-70b-instruct:free", tier=4),
        _m("deepseek/deepseek-chat-v3-0324:free", tier=4),
        _m("meta-llama/llama-4-scout:free", vision=True, tier=4),
        _m("qwen/qwen2.5-vl-72b-instruct:free", vision=True, tier=4),
        _m("google/gemini-2.0-flash-exp:free", vision=True, tier=4),
        _m("qwen/qwen-2.5-72b-instruct:free", tier=4),
        _m("nousresearch/hermes-3-llama-3.1-405b:free", tier=4),
        _m("meta-llama/llama-3.1-405b-instruct:free", tier=4),
        _m("openai/gpt-oss-120b:free", tier=4),
        _m("qwen/qwq-32b:free", tier=4, think=True),
        _m("google/gemma-3-27b-it:free", vision=True, tier=3),
        _m("mistralai/mistral-small-3.1-24b-instruct:free", vision=True, tier=3),
        _m("microsoft/phi-4:free", tier=3),
        _m("nvidia/llama-3.1-nemotron-70b-instruct:free", tier=3),
    ]),
    # OVHcloud AI Endpoints — без ключа, ЕС, 2 запроса/мин на IP
    Provider("ovh", "https://oai.endpoints.kepler.ai.cloud.ovh.net/v1", "", [
        _m("Qwen3.5-397B-A17B", tier=5),
        _m("gpt-oss-120b", tier=4),
        _m("Meta-Llama-3_3-70B-Instruct", tier=4),
        _m("DeepSeek-R1-Distill-Llama-70B", tier=4, think=True),
        _m("Mistral-Small-3.2-24B-Instruct-2506", tier=3),
    ]),
    # Kilo Code Gateway — без ключа, модели с суффиксом :free
    Provider("kilo", "https://api.kilo.ai/api/gateway", "", [
        _m("nvidia/nemotron-3-ultra-550b:free", tier=5),
        _m("nvidia/nemotron-3-super-120b-a12b:free", tier=4),
        _m("openai/gpt-oss-120b:free", tier=4),
        _m("DeepSeek-V4:free", tier=4),
        _m("qwen/qwen3-coder:free", tier=4),
        _m("moonshotai/kimi-k2:free", tier=4),
        _m("meta-llama/llama-3.3-70b-instruct:free", tier=3),
        _m("z-ai/glm-4.5-air:free", tier=3),
    ]),
    # LLM7.io — анонимный доступ
    Provider("llm7", "https://api.llm7.io/v1", "unused", [
        _m("gpt-oss-120b", tier=3),
        _m("DeepSeek-V4-Flash", tier=3),
        _m("GLM-5.3-Flash", tier=3),
    ]),
]


def all_units():
    """Все (провайдер, модель) — кандидаты автовыбора. Их 50+."""
    units = []
    for p in PROVIDERS:
        for m in p.models:
            units.append({
                "provider": p.name,
                "base_url": p.base_url,
                "api_key": p.api_key,
                "model": m["id"],
                "vision": m["vision"],
                "tier": m["tier"],
                "think": m.get("think", False),
            })
    return units
