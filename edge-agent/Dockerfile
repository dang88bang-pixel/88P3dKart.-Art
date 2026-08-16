FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    AGENT_DB_PATH=/data/agent.db \
    AGENT_AUTH_DB_PATH=/data/credentials.db \
    AGENT_ALARM_DB_PATH=/data/alarms.db \
    AGENT_LOG_DIR=/var/log/agent \
    AGENT_LOOP_HZ=20

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt \
    && groupadd --gid 10001 agent \
    && useradd --uid 10001 --gid agent --no-create-home --shell /usr/sbin/nologin agent \
    && mkdir -p /data /var/log/agent \
    && chown -R agent:agent /app /data /var/log/agent

COPY --chown=agent:agent . .

USER 10001:10001

VOLUME ["/data", "/var/log/agent"]
EXPOSE 8080

CMD ["python", "agent.py"]
