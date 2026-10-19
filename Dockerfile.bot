FROM python:3.12-slim
ENV PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements.txt ./
RUN pip install -r requirements.txt
COPY aibot ./aibot
COPY run.py ./
# История диалогов живёт в bot.db внутри контейнера (до 3 МБ на пользователя)
CMD ["python", "run.py"]
