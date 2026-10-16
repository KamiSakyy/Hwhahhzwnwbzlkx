# TG-DOOM — Railway (2026): a Dockerfile in the repo ROOT is auto-detected
# and always wins over Railpack. Health endpoint listens on $PORT.
FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

# ALL project files live in the root — no subfolders, Railway sees everything
COPY bot.py doom_engine.py ./

RUN useradd --create-home appuser && chown -R appuser /app
USER appuser

# Railway injects $PORT; bot.py serves /health there (default 8000 locally)
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s \
    CMD python -c "import os,urllib.request;urllib.request.urlopen(f'http://127.0.0.1:{os.getenv(\"PORT\",\"8000\")}/health',timeout=4)" || exit 1

CMD ["python", "bot.py"]
